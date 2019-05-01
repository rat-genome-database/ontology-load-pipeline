package edu.mcw.rgd.dataload.ontologies;

import edu.mcw.rgd.dao.impl.*;
import edu.mcw.rgd.dao.spring.IntStringMapQuery;
import edu.mcw.rgd.dao.spring.StringListQuery;
import edu.mcw.rgd.dao.spring.StringMapQuery;
import edu.mcw.rgd.datamodel.*;
import edu.mcw.rgd.datamodel.ontologyx.*;
import edu.mcw.rgd.pipelines.PipelineSession;
import edu.mcw.rgd.process.Utils;
import org.apache.log4j.Logger;
import org.springframework.jdbc.object.BatchSqlUpdate;

import java.sql.*;
import java.util.*;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * wrapper class to manage all interactions with database
 */
public class OntologyDAO {

    AnnotationDAO annotDAO = new AnnotationDAO();
    MapDAO mapDAO = new MapDAO();
    OmimDAO omimDAO = new OmimDAO();
    OntologyXDAO dao = new OntologyXDAO();
    PathwayDAO pathwayDAO = new PathwayDAO();
    RGDManagementDAO rgdDAO = new RGDManagementDAO();

    protected final Logger logErrors = Logger.getLogger("errors");
    protected final Logger logInsertedTerms = Logger.getLogger("insertedTerms");
    protected final Logger logInsertedXRefs = Logger.getLogger("insertedXRefs");
    protected final Logger logDeletedXRefs = Logger.getLogger("deletedXRefs");
    protected final Logger logDescChangedXRefs = Logger.getLogger("descChangedXRefs");
    protected final Logger logInsertedDags = Logger.getLogger("insertedDags");
    protected final Logger logDeletedDags = Logger.getLogger("deletedDags");

    /**
     * get ontology object given ont_id
     * @param ontId ontology id
     * @return Ontology object or null if ont_id is invalid
     * @throws Exception if something wrong happens in spring framework
     */
    public Ontology getOntology(String ontId) throws Exception {
        return dao.getOntology(ontId);
    }

    /**
     * get an ontology term given term accession id
     * @param termAcc term accession id
     * @return OntTerm object if given term found in database or null otherwise
     * @throws Exception if something wrong happens in spring framework
     */
    public Term getTerm(String termAcc) throws Exception {

        return termAcc==null ? null : dao.getTermWithStatsCached(termAcc);
    }

    public Term getRdoTermByTermName(String term) throws Exception {

        // original query ic case sensitive
        //return dao.getTermByTermName(term, "RDO");

        String sql = "SELECT * FROM ont_terms WHERE LOWER(term)=LOWER(?) AND ont_id=?";
        List terms = dao.executeTermQuery(sql, new Object[]{term, "RDO"});
        return terms.isEmpty() ? null : (Term)terms.get(0);
    }

    /**
     * insert ontology term into database if it does not exist
     * @param term OntTerm object to be inserted
     * @param session PipelineSession object
     * @throws Exception if something wrong happens in spring framework
     */
    public void insertTerm(Term term, PipelineSession session) throws Exception {

        // extra check: ids parentTermAcc normalized
        fixTermNameAndDefinition(term);
        int r = dao.insertTerm(term);
        if( r!=0 ) {
            logInsertedTerms.info("INSERT|"+term.dump("|"));
            session.incrementCounter("TERMS_INSERTED_"+term.getOntologyId(), 1);
        }
    }

    /**
     * update ontology term in the database
     * @param term OntTerm object to be inserted
     * @return count of rows affected
     * @throws Exception if something wrong happens in spring framework
     */
    public int updateTerm(Term term) throws Exception {

        fixTermNameAndDefinition(term);
        return dao.updateTerm(term);
    }

    private void fixTermName(Term term) {
        // sometimes terms with null term names are found in database (due to a logic flaw in the pipeline?)
        // so we fix that here: if term name is not given, it will be set to a single space
        String termName = term.getTerm();
        if( termName==null || termName.trim().isEmpty() ) {
            term.setTerm(" ");

            // log all occurrences of fixed term names to error log
            logErrors.warn("term "+term.getAccId()+" has no term name");
        }

        if(Utils.isStringEmpty(term.getAccId()) ) {
            logErrors.warn("term "+termName+" has no term acc!!!");
        }
    }

    private void fixTermNameAndDefinition(Term term) {
        fixTermName(term);

        // fix term definition: replace any tabs and new lines with spaces
        String txt = term.getDefinition();
        if( txt!=null ) {
            if( txt.contains("\t") ) {
                txt = txt.replace('\t', ' ');
                term.setDefinition(txt);
            }
            if( txt.contains("\n") ) {
                txt = txt.replace('\n', ' ');
                term.setDefinition(txt);
            }
            if( txt.contains("\r") ) {
                txt = txt.replace('\r', ' ');
                term.setDefinition(txt);
            }
        }
    }

    /**
     * delete all dags for given ontology
     * @param ontId ontology id
     * @param cutoffDate cut off date
     * @return count of rows affected
     * @throws Exception if something wrong happens in spring framework
     */
    public int deleteDags(String ontId, Date cutoffDate) throws Exception {

        for( TermDagEdge dag: dao.getStaleDags(ontId, cutoffDate) ) {
            logDeletedDags.info(dag.dump("|"));
        }
        return dao.deleteStaleDags(ontId, cutoffDate);
    }

    /**
     * dump to log file all inserted dags for given ontology
     * @param ontId ontology id
     * @param cutoffDate cut off date
     * @return count of rows affected
     * @throws Exception if something wrong happens in spring framework
     */
    public int dumpInsertedDags(String ontId, Date cutoffDate) throws Exception {

        List<TermDagEdge> newDags = dao.getNewDags(ontId, cutoffDate);
        for( TermDagEdge dag: newDags ) {
            logInsertedDags.info(dag.dump("|"));
        }
        return newDags.size();
    }

    /**
     * insert a new dag edge into ONT_DAG table
     * @param parentTermAcc parent term accession id
     * @param childTermAcc child term accession id
     * @param relId relation id
     * @throws Exception if something wrong happens in spring framework
     */
    public int insertDag(String parentTermAcc, String childTermAcc, String relId) throws Exception {

        return dao.upsertDag(parentTermAcc, childTermAcc, relId);
    }

    /**
     * get list of all synonyms for given term
     * @param termAcc term accession id
     * @return list of all synonyms
     * @throws Exception if something wrong happens in spring framework
     */
    public List<TermSynonym> getTermSynonyms(String termAcc) throws Exception {

        List<TermSynonym> results = _termSynonymCache.get(termAcc);
        if( results==null ) {
            results = dao.getTermSynonyms(termAcc);
            _termSynonymCache.put(termAcc, results);
        }
        return results;
    }
    Map<String, List<TermSynonym>> _termSynonymCache = new ConcurrentHashMap<>();


    public List<Term> getRdoTermsBySynonym(String synonymToMatch) throws Exception {
        return dao.getTermsBySynonym("RDO", synonymToMatch, "exact");
    }

    /**
     * get terms synonyms of given type within a specified ontology
     * @param ontologyId id of ontology to be searched for; must not be null
     * @param synonymType synonym type
     * @return List of matching TermSynonym objects; could be empty
     * @throws Exception if something wrong happens in spring framework
     */
    public List<TermSynonym> getActiveSynonymsByType(String ontologyId, String synonymType) throws Exception {

        return dao.getActiveSynonymsByType(ontologyId, synonymType);
    }

    public List<TermSynonym> getActiveSynonymsByName(String ontologyId, String synonymName) throws Exception {
        return dao.getActiveSynonymsByName(ontologyId, synonymName);
    }

    /**
     * insert new synonym for given term
     * @param synonym OntTermSynonym object to be inserted
     * @param source synonym source
     * @throws Exception if something wrong happens in spring framework
     * @return true if synonym was inserted; false if it was skipped
     */
    public boolean insertTermSynonym(TermSynonym synonym, String source) throws Exception {
        synonym.setCreatedDate(new Date());
        synonym.setLastModifiedDate(synonym.getCreatedDate());
        synonym.setSource(source==null ? "OBO" : source);

        Logger log = Logger.getLogger("synonymsInserted");
        log.info(synonym.dump("|"));

        synonym.setKey(dao.insertTermSynonym(synonym));
        return true;
    }

    /**
     * update last modification date for a list of synonyms
     * @param synonyms collection of TermSynonym objects
     * @return count of rows affected
     * @throws Exception if something wrong happens in spring framework
     */
    public int updateTermSynonymLastModifiedDate(Collection<TermSynonym> synonyms) throws Exception {
        return dao.updateTermSynonymLastModifiedDate(synonyms);
    }

    /**
     * delete a collection of term synonyms
     * @param synonyms collection of term synonyms
     * @return count of rows affected
     * @throws Exception if something wrong happens in spring framework
     */
    public int deleteTermSynonyms(Collection<TermSynonym> synonyms) throws Exception {

        if( synonyms.isEmpty() ) {
            return 0;
        }

        Logger log = Logger.getLogger("synonymsDeleted");
        for( TermSynonym syn: synonyms ) {
            log.info(syn.dump("|"));
        }

        return dao.deleteTermSynonyms(synonyms);
    }

    /**
     * get all ontology synonyms for given source modified before given date and time
     *
     * @param ontId id of ontology to be processed
     * @param source source of term synonyms
     * @param dt cut-off date of last modification
     * @return list of TermSynonym objects
     * @throws Exception on spring framework dao failure
     */
    public List<TermSynonym> getTermSynonymsModifiedBefore(String ontId, String source, Date dt) throws Exception{

        return dao.getTermSynonymsModifiedBefore(ontId, source, dt);
    }

    public List<String> getActiveParentTerms(String termAcc) throws Exception {
        String sql = "SELECT t.term_acc FROM ont_dag d,ont_terms t WHERE child_term_acc=? AND parent_term_acc=t.term_acc AND t.is_obsolete=0";
        return StringListQuery.execute(dao, sql, termAcc);
    }

    public Map<String,String> getAnchorTerms(String rdoTermAcc, String anchorTerm) throws Exception {

        Map<String,String> results = new TreeMap<>();
        for( StringMapQuery.MapPair pair: dao.getAnchorTerms(rdoTermAcc, anchorTerm) ) {
            results.put(pair.keyValue, pair.stringValue);
        }
        return results;
    }

    /**
     * get list of accession ids for terms matching the prefix
     * @param prefix term acc prefix
     * @return list of term accession ids
     * @throws Exception if something wrong happens in spring framework
     */
    public List<String> getAllTermAccIds(String prefix) throws Exception {

        if( prefix.equals("*") )
            prefix = "DOID:";

        String sql = "SELECT t.term_acc "+
            "FROM ont_terms t,ont_term_stats2 s "+
            "WHERE t.term_acc like ? AND t.term_acc=s.term_acc(+) "+
            " AND s.stat_name(+)='child_term_count' "+
            "ORDER BY NVL(stat_value,0) DESC,term_acc";
        return StringListQuery.execute(dao, sql, prefix+"%");
    }

    public List<Integer> getAnnotatedObjectIds(String accId, boolean withChildren, int speciesTypeKey, int objectKey) throws Exception {
        return annotDAO.getAnnotatedObjectIds(accId, withChildren, speciesTypeKey, objectKey);
    }

    public List<IntStringMapQuery.MapPair> getAnnotatedRgdIds(String accId, int speciesTypeKey) throws Exception {
        return annotDAO.getAnnotatedObjectIdsAndTerms(accId, speciesTypeKey);
    }

    /**
     * return nr of descendant terms for given term
     * @param termAcc term accession id
     * @return count of descendant terms
     * @throws Exception if something wrong happens in spring framework
     */
    public int getDescendantCount(String termAcc) throws Exception {

        return dao.getCountOfDescendants(termAcc);
    }

    /**
     * get active (non-obsolete) descendant (child) terms of given term, recursively
     * @param termAcc term accession id
     * @return list of descendant terms
     * @throws Exception if something wrong happens in spring framework
     */
    public List<Term> getAllActiveTermDescendants(String termAcc) throws Exception {
        return dao.getAllActiveTermDescendants(termAcc);
    }

    public List<String> getAllActiveTermDescendantAccIds(String termAcc) throws Exception {
        String sql = "SELECT DISTINCT child_term_acc FROM ont_dag START WITH parent_term_acc=? CONNECT BY PRIOR child_term_acc=parent_term_acc";
        return StringListQuery.execute(dao, sql, termAcc);
    }

    /**
     * return nr of ancestor terms for given term
     * @param termAcc term accession id
     * @return count of ancestor terms
     * @throws Exception if something wrong happens in spring framework
     */
    public int getAncestorCount(String termAcc) throws Exception {
        return dao.getCountOfAncestors(termAcc);
    }

    public int getAnnotatedObjectCount(String accId, int objectKey, int speciesTypeKey, boolean withChildren) throws Exception {
        return annotDAO.getAnnotatedObjectCount(accId, withChildren, speciesTypeKey, objectKey);
    }

    private static Map<String,Set<Integer>> filterIdList = new ConcurrentHashMap<>();

    public int getAnnotatedObjectCount(String accId, int objectKey, int speciesTypeKey, boolean withChildren, String filter) throws Exception {

        if (filter == null || filter.equals("")) {
            return getAnnotatedObjectCount(accId, objectKey, speciesTypeKey, withChildren);
        }

        List<Integer> ids = annotDAO.getAnnotatedObjectIds(accId, withChildren, speciesTypeKey, objectKey);


        String key = objectKey + "-" + speciesTypeKey + "-" + withChildren + "-" + filter;

        Set<Integer> filterIds = filterIdList.get(key);
        if( filterIds==null ) {
            filterIds = new HashSet<>(annotDAO.getAnnotatedObjectIds(filter, withChildren, speciesTypeKey, objectKey));
            filterIdList.put(key, filterIds);
        }

        int cnt = 0;
        for (Integer id: ids) {
            if (filterIds.contains(id)) {
                cnt++;
            }
        }

        return cnt;
    }

    public TermStats getGViewerStats(String termAcc) throws Exception {
        TermStats ts = getTermWithStats(termAcc);

        String sql = "SELECT rat_gviewer_for_term, rat_gviewer_with_children, " +
                "human_gviewer_for_term, human_gviewer_with_children, " +
                "mouse_gviewer_for_term, mouse_gviewer_with_children " +
                "FROM ont_term_stats WHERE term_acc=?";

        Connection conn = null;
        try {
            conn = dao.getConnection();
            PreparedStatement ps = conn.prepareStatement(sql);
            ps.setString(1, termAcc);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                ts.setXmlForTerm(rs.getString("rat_gviewer_for_term"), SpeciesType.RAT);
                ts.setXmlWithChilds(rs.getString("rat_gviewer_with_children"), SpeciesType.RAT);
                ts.setXmlForTerm(rs.getString("human_gviewer_for_term"), SpeciesType.HUMAN);
                ts.setXmlWithChilds(rs.getString("human_gviewer_with_children"), SpeciesType.HUMAN);
                ts.setXmlForTerm(rs.getString("mouse_gviewer_for_term"), SpeciesType.MOUSE);
                ts.setXmlWithChilds(rs.getString("mouse_gviewer_with_children"), SpeciesType.MOUSE);
            }
        } finally {
            try {
                conn.close();
            } catch (Exception ignored) {
            }
        }
        return ts;
    }

    public TermStats getTermWithStats(String termAcc) throws Exception {
        return getTermWithStats(termAcc, null);
    }

    public TermStats getTermWithStats(String termAcc, String filter) throws Exception {

        TermStats ts = new TermStats();
        ts.setTermAccId(termAcc);
        ts.term = dao.getTermWithStats(termAcc, null,filter);
        return ts;
    }

    /**
     * update term stats
     * @param stats term stats: term accession id + annotation counts
     * @throws Exception if something wrong happens in spring framework
     */
    public void updateTermStats(TermStats stats) throws Exception {

        // stat update
        if( !stats.statsAreDirty ) {
            return;
        }

        if( stats.getFilter()==null ) {
            updateTermStatsWithoutFilter(stats);
        } else {
            updateTermStatsWithFilter(stats);
        }
    }

    void updateTermStatsWithoutFilter(TermStats stats) throws Exception {
        // stats to be added
        if( !stats.statsToBeAdded.isEmpty() ) {
            BatchSqlUpdate bsu = new BatchSqlUpdate(annotDAO.getDataSource(),
                "INSERT INTO ont_term_stats2 "+
                "(term_acc,last_modified_date,species_type_key,object_key,with_children,stat_name,stat_value,filter) "+
                "VALUES(?,SYSDATE,?,?,?,?,?,NULL)",
                new int[]{Types.VARCHAR, Types.INTEGER, Types.INTEGER, Types.INTEGER, Types.VARCHAR, Types.INTEGER});
            bsu.compile();
            for( TermStat ts: stats.statsToBeAdded ) {
                bsu.update(ts.getTermAcc(), ts.getSpeciesTypeKey(), ts.getObjectKey(), ts.getWithChildren(),
                        ts.getStatName(), ts.getStatValue());
            }

            annotDAO.executeBatch(bsu);
        }

        // stats to be deleted
        if( !stats.statsToBeDeleted.isEmpty() ) {
            BatchSqlUpdate bsu = new BatchSqlUpdate(annotDAO.getDataSource(),
                "DELETE FROM ont_term_stats2 "+
                "WHERE term_acc=? AND species_type_key=? AND object_key=? AND with_children=? AND stat_name=? AND filter IS NULL",
                new int[]{Types.VARCHAR, Types.INTEGER, Types.INTEGER, Types.INTEGER, Types.VARCHAR});
            bsu.compile();
            for( TermStat ts: stats.statsToBeDeleted ) {
                bsu.update(ts.getTermAcc(), ts.getSpeciesTypeKey(), ts.getObjectKey(), ts.getWithChildren(),
                        ts.getStatName());
            }
            annotDAO.executeBatch(bsu);
        }

        // stats to be updated
        if( !stats.statsToBeUpdated.isEmpty() ) {
            BatchSqlUpdate bsu = new BatchSqlUpdate(annotDAO.getDataSource(),
                "UPDATE ont_term_stats2 "+
                "SET stat_value=?,last_modified_date=SYSDATE "+
                "WHERE term_acc=? AND species_type_key=? AND object_key=? AND with_children=? AND stat_name=? AND filter IS NULL",
                new int[]{Types.INTEGER, Types.VARCHAR, Types.INTEGER, Types.INTEGER, Types.INTEGER, Types.VARCHAR});
            bsu.compile();
            for( TermStat ts: stats.statsToBeUpdated ) {
                bsu.update(ts.getStatValue(), ts.getTermAcc(), ts.getSpeciesTypeKey(), ts.getObjectKey(),
                        ts.getWithChildren(), ts.getStatName());
            }
            annotDAO.executeBatch(bsu);
        }
    }

    void updateTermStatsWithFilter(TermStats stats) throws Exception {
        // stats to be added
        if( !stats.statsToBeAdded.isEmpty() ) {
            BatchSqlUpdate bsu = new BatchSqlUpdate(annotDAO.getDataSource(),
                "INSERT INTO ont_term_stats2 "+
                "(term_acc,last_modified_date,species_type_key,object_key,with_children,stat_name,stat_value,filter) "+
                "VALUES(?,SYSDATE,?,?,?,?,?,?)",
                new int[]{Types.VARCHAR, Types.INTEGER, Types.INTEGER, Types.INTEGER, Types.VARCHAR, Types.INTEGER, Types.VARCHAR});
            bsu.compile();
            for( TermStat ts: stats.statsToBeAdded ) {
                bsu.update(ts.getTermAcc(), ts.getSpeciesTypeKey(), ts.getObjectKey(), ts.getWithChildren(),
                        ts.getStatName(), ts.getStatValue(), ts.getFilter());
            }

            annotDAO.executeBatch(bsu);
        }

        // stats to be deleted
        if( !stats.statsToBeDeleted.isEmpty() ) {
            BatchSqlUpdate bsu = new BatchSqlUpdate(annotDAO.getDataSource(),
                "DELETE FROM ont_term_stats2 "+
                "WHERE term_acc=? AND species_type_key=? AND object_key=? AND with_children=? AND stat_name=? AND filter=?",
                new int[]{Types.VARCHAR, Types.INTEGER, Types.INTEGER, Types.INTEGER, Types.VARCHAR, Types.VARCHAR});
            bsu.compile();
            for( TermStat ts: stats.statsToBeDeleted ) {
                bsu.update(ts.getTermAcc(), ts.getSpeciesTypeKey(), ts.getObjectKey(), ts.getWithChildren(),
                        ts.getStatName(), ts.getFilter());
            }
            annotDAO.executeBatch(bsu);
        }

        // stats to be updated
        if( !stats.statsToBeUpdated.isEmpty() ) {
            BatchSqlUpdate bsu = new BatchSqlUpdate(annotDAO.getDataSource(),
                "UPDATE ont_term_stats2 "+
                "SET stat_value=?,last_modified_date=SYSDATE "+
                "WHERE term_acc=? AND species_type_key=? AND object_key=? AND with_children=? AND stat_name=? AND filter=?",
                new int[]{Types.INTEGER, Types.VARCHAR, Types.INTEGER, Types.INTEGER, Types.INTEGER, Types.VARCHAR, Types.VARCHAR});
            bsu.compile();
            for( TermStat ts: stats.statsToBeUpdated ) {
                bsu.update(ts.getStatValue(), ts.getTermAcc(), ts.getSpeciesTypeKey(), ts.getObjectKey(),
                        ts.getWithChildren(), ts.getStatName(), ts.getFilter());
            }
            annotDAO.executeBatch(bsu);
        }
    }

    // return 0: up-to-date, -1: inserted, 1:updated
    public int updateGViewerStats(TermStats stats) throws Exception {

        if( !stats.xmlIsDirty ) {
            return 0;
        }

        // xml gviewer update
        String sql =
            "UPDATE ont_term_stats SET "+
            "rat_gviewer_for_term=?, rat_gviewer_with_children=?, "+
            "human_gviewer_for_term=?, human_gviewer_with_children=?, "+
            "mouse_gviewer_for_term=?, mouse_gviewer_with_children=?, "+
            "last_modified_date=SYSDATE WHERE term_acc=?";

        int rowsAffected = dao.update(sql,
            stats.getXmlForTerm(SpeciesType.RAT), stats.getXmlWithChilds(SpeciesType.RAT),
            stats.getXmlForTerm(SpeciesType.HUMAN), stats.getXmlWithChilds(SpeciesType.HUMAN),
            stats.getXmlForTerm(SpeciesType.MOUSE), stats.getXmlWithChilds(SpeciesType.MOUSE),
            stats.getTermAccId());

        // if no rows affected, we can insert new stats
        if( rowsAffected==0 ) {
            sql =
                "INSERT INTO ont_term_stats ("+
                "rat_gviewer_for_term, rat_gviewer_with_children, "+
                "human_gviewer_for_term, human_gviewer_with_children, "+
                "mouse_gviewer_for_term, mouse_gviewer_with_children, "+
                "term_acc) " +
                "values(?,?,?,?,?,?,?)";

            dao.update( sql,
                stats.getXmlForTerm(SpeciesType.RAT), stats.getXmlWithChilds(SpeciesType.RAT),
                stats.getXmlForTerm(SpeciesType.HUMAN), stats.getXmlWithChilds(SpeciesType.HUMAN),
                stats.getXmlForTerm(SpeciesType.MOUSE), stats.getXmlWithChilds(SpeciesType.MOUSE),
                stats.getTermAccId());

            return -1;
        }
        return 1;
    }

    /**
     * examines all terms and all active terms that do not appear in ontology dag trees
     * receive 'obsolete' status = 2
     * @param ontId ontology id
     * @return count of terms made obsolete
     * @throws Exception if something wrong happens in spring framework
     */
    int obsoleteOrphanedTerms(String ontId) throws Exception {

        // fix for GO
        if( ontId.equals("GO") ) {
            return obsoleteOrphanedTerms("BP") + obsoleteOrphanedTerms("MF") + obsoleteOrphanedTerms("CC");
        }

        // check if there are orphaned terms
        List<Term> orphanedTerms = dao.getOrphanedTerms(ontId);
        if( orphanedTerms.isEmpty() )
            return 0; // no  orphaned terms

        // never obsolete terms for RDO ontology! only report the issue
        if( ontId.equals("RDO") ) {
            System.out.println("OBSOLETE RDO TERMS: ");
            for( Term term: orphanedTerms ) {
                System.out.println("  "+term.dump("|"));
            }
            return 0;
        }

        // dump terms to be obsoleted into 'obsoletedTerms.log'
        Logger log = Logger.getLogger("obsoletedTerms");
        for( Term term: orphanedTerms ) {
            log.info(term.dump("|"));
        }

        // finally obsolete the orphaned terms
        return dao.obsoleteOrphanedTerms(ontId);
    }

    /**
     * this method examined all term accession ids, and if the terms are not in database, they are inserted
     * @param terms list of terms
     * @param session PipelineSession object
     * @throws Exception if something wrong happens in spring framework
     */
    public synchronized void ensureTermsAreInDatabase(List<Term> terms, PipelineSession session) throws Exception {

        for( Term term: terms ) {
            if( term.getAccId()==null ) {
                continue;
            }
            Term termInDb = getTerm(term.getAccId());
            if( termInDb==null ) {
                insertTerm(term, session);
            }
        }
    }

    /**
     * drop all synonyms for given ontology
     * @param ontId ontology id
     * @return count of dropped synonyms
     * @throws Exception if something wrong happens in spring framework
     */
    public int dropSynonyms(String ontId) throws Exception {

        return dao.dropTermSynonymsForOntology(ontId);
    }

    /**
     * get map key for primary ref assembly
     * @param speciesTypeKey species type key
     * @return map key for primary ref assembly
     * @throws Exception if something wrong happens in spring framework
     */
    public int getPrimaryRefAssemblyMapKey(int speciesTypeKey) throws Exception {

        return mapDAO.getPrimaryRefAssembly(speciesTypeKey).getKey();
    }

    /** get map data for given rgd id and map key;
     * internally we use cache to reduce number of requests to database
     * @param rgdId object rgd id
     * @param mapKey object map key
     * @return list of MapData objects
     * @throws Exception if something wrong happens in spring framework
     */
    synchronized public List<MapData> getMapData(int rgdId, int mapKey) throws Exception {

        // make cache key=RgdId:MapKey
        String cacheKey = rgdId+":"+mapKey;
        // retrieve data from cache
        List<MapData> results = _cacheMapData.get(cacheKey);
        if( results==null ) {
            // not in cache -- retrieve data from database
            results = mapDAO.getMapData(rgdId, mapKey);
            // put data to cache
            _cacheMapData.put(cacheKey, results);
        }
        return results;
    }
    static private Map<String, List<MapData>> _cacheMapData = new HashMap<>(50003);


    public List<TermXRef> getTermXRefs(String termAcc) throws Exception {
        return dao.getTermXRefs(termAcc);
    }

    public int insertTermXRefs(List<TermXRef> xrefs) throws Exception {
        for( TermXRef xref: xrefs ) {
            logInsertedXRefs.info(xref.dump("|"));
            dao.insertTermXRef(xref);
        }
        return xrefs.size();
    }

    public int deleteTermXRefs(List<TermXRef> xrefs) throws Exception {
        for( TermXRef xref: xrefs ) {
            logDeletedXRefs.info(xref.dump("|"));
            dao.deleteTermXRef(xref);
        }
        return xrefs.size();
    }

    public int updateTermXRefDescriptions(List<TermXRef> xrefs) throws Exception {
        for( TermXRef xref: xrefs ) {
            logDescChangedXRefs.info(xref.dump("|"));
            dao.updateTermXRefDescription(xref);
        }
        return xrefs.size();
    }

    /**
     * check if a given term is a descendant, either direct or indirect descendant of a given term;
     * f.e. 'inbred strain' / 'SS' / 'SS/Jr' <br>
     *      term 'SS/Jr' is a (direct) descendant of ancestor term 'SS' <br>
     *      term 'SS/Jr' is a (indirect) descendant of ancestor term 'inbred strain' <br>
     * @param termAcc accession id of the term in question
     * @param ancestorTermAcc accession id of the ancestor term
     * @return true if the term is a descendant of the ancestor term
     * @throws Exception if something wrong happens in spring framework
     */
    public boolean isDescendantOf(String termAcc, String ancestorTermAcc) throws Exception {
        if( termAcc.equals(ancestorTermAcc) ) {
            return false;
        }
        return dao.isDescendantOf(termAcc, ancestorTermAcc);
    }

    /**
     * get diagram count for a given pathway term
     * @param pathwayAccId pathway term accession id
     * @param withChildren if true also examine the pathway child terms
     * @return count of diagrams for given pathway term
     * @throws Exception if something wrong happens in spring framework
     */
    public int getDiagramCount(String pathwayAccId, boolean withChildren) throws Exception {

        int diagramCount = 0;
        if( withChildren ) {
            diagramCount = pathwayDAO.getCountOfCuratedPathwaysForTermDescendants(pathwayAccId);
            // note: this is the diagram count only for the child terms
        }

        if( pathwayDAO.getPathway(pathwayAccId) !=null ) {
            diagramCount++;
        }
        return diagramCount;
    }


    public List<StringMapQuery.MapPair> getDagForOntologyPrefix(String ontPrefix) throws Exception {
        String sql = "SELECT parent_term_acc,child_term_acc FROM ont_dag WHERE parent_term_acc like '"+ontPrefix+"%'";
        return StringMapQuery.execute(dao, sql);
    }

    public int getObjectKey(int rgdId) throws Exception {
        Integer objectKey = _objectKeyCache.get(rgdId);
        if( objectKey!=null ) {
            return objectKey;
        }
        RgdId id = rgdDAO.getRgdId2(rgdId);
        if( id==null ) {
            objectKey = 0;
        } else {
            objectKey = id.getObjectKey();
        }
        _objectKeyCache.put(rgdId, objectKey);
        return objectKey;
    }

    Map<Integer,Integer> _objectKeyCache = new ConcurrentHashMap<>();

    public String getObjectSymbol(int rgdId) throws Exception {
        String objectSymbol = _objectSymbolCache.get(rgdId);
        if( objectSymbol!=null ) {
            return objectSymbol;
        }
        Object o = rgdDAO.getObject(rgdId);
        if( o==null || !(o instanceof ObjectWithSymbol) ) {
            objectSymbol = "";
        } else {
            objectSymbol = ((ObjectWithSymbol)o).getSymbol();
        }
        _objectSymbolCache.put(rgdId, objectSymbol);
        return objectSymbol;
    }

    Map<Integer,String> _objectSymbolCache = new ConcurrentHashMap<>();


    public void checkForCycles(String ontId) throws Exception {
        List<String> accIds;
        if( ontId==null ) {
            String sql = "SELECT term_acc FROM ont_terms WHERE is_obsolete=0";
            accIds = StringListQuery.execute(dao, sql);
        } else {
            String sql = "SELECT term_acc FROM ont_terms WHERE is_obsolete=0 AND ont_id=?";
            accIds = StringListQuery.execute(dao, sql, ontId);
        }
        Collections.shuffle(accIds);
        System.out.println("active terms loaded: "+accIds.size()+" progress interval 15sec");

        long time0 = System.currentTimeMillis();
        final AtomicLong[] stats = {new AtomicLong(time0), new AtomicLong(0)};
        accIds.parallelStream().forEach(accId -> {
            // test if the newly inserted DAG does not form loops
            try {
                getDescendantCount(accId);

                // every 10sec print out progress
                long accIdsProcessed = stats[1].incrementAndGet();
                long time2 = System.currentTimeMillis();
                long time1 = stats[0].get();
                if( time2- time1 > 15000 ) {
                    stats[0].set(time2);
                    long percent = (100 * accIdsProcessed) / accIds.size();
                    System.out.println(accIdsProcessed + " ("+ percent+"%),  threads=" + Thread.activeCount());
                }
            } catch(Exception e) {
                System.out.println("WARNING: CYCLE found for "+accId);


            }
        });
        long accIdsProcessed = stats[1].get();
        System.out.println(accIdsProcessed + ". threads=" + Thread.activeCount());
        System.out.println("===DONE=== "+Utils.formatElapsedTime(time0, System.currentTimeMillis()));
    }

    public boolean isOmimIdInactive(String omimId) throws Exception {
        Omim omim = omimDAO.getOmimByNr(omimId.substring(5));
        return omim!=null && !omim.getStatus().equals("live");
    }
}
