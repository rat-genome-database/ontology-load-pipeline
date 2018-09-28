package edu.mcw.rgd.dataload.ontologies;

import edu.mcw.rgd.datamodel.ontologyx.*;
import edu.mcw.rgd.pipelines.*;
import edu.mcw.rgd.process.Utils;
import org.apache.commons.collections.CollectionUtils;
import org.apache.log4j.Logger;

import java.util.*;

/**
 * @author mtutaj
 * <p>
 * generate DOID synonyms for RDO terms, by matching RDO terms against DO terms
 */
public class DoIdMapper {

    /** OBSOLETE AS OF JAN 1 2018

    public static final String RDO_DO_ROUGH_MAPPER = "RDO_DO_ROUGH_MAPPER";
    private OntologyDAO dao;
    private String version;

    protected Logger log;
    protected Logger logDoTermsNotMatchingRdo;
    protected Logger logRdoTermsNotMatchingDo;
    protected Logger logDoTermsMatchingRdo;

    protected Map<String,Set<Term>> rdoSynonyms = new HashMap<>();
    protected SynonymManager synonymManager = new SynonymManager();
    String doAnchorTerm; // DO anchor term: 'disease of anatomical entity'
    List<TermWithStats> doTopLevelTerms;

    public OntologyDAO getDao() {
        return dao;
    }

    public void setDao(OntologyDAO dao) {
        this.dao = dao;
    }


    public void run(int qcThreadCount) throws Exception {
        long time0 = System.currentTimeMillis();

        // init loggers
        log = Logger.getLogger("doIdMapper");
        logDoTermsNotMatchingRdo = Logger.getLogger("doTermsNotMatchingRdo");
        logRdoTermsNotMatchingDo = Logger.getLogger("rdoTermsNotMatchingDo");
        logDoTermsMatchingRdo = Logger.getLogger("doTermsMatchingRdo");

        System.out.println(getVersion());
        log.info(getVersion());

        doAnchorTerm = "DOID:7";
        doTopLevelTerms = dao.getActiveChildTerms(doAnchorTerm);
        StringBuilder header = new StringBuilder();
        header.append("|DOID");
        header.append("|DO term name");
        for (TermWithStats rdoTopLevelTerm : doTopLevelTerms) {
            header.append("|").append(rdoTopLevelTerm.getTerm());
        }
        logDoTermsNotMatchingRdo.info(header.toString());

        System.out.println("Indexing RDO synonyms ...");
        indexRdoSynonyms();
        System.out.println("RDO synonyms indexed: "+rdoSynonyms.size());

        System.out.println("Starting DO ontology analysis ...");

        Date processingStartTime = new Date();

        PipelineManager manager = new PipelineManager();
        // first thread group: read all DO ontology terms
        manager.addPipelineWorkgroup(new PreProcessor(), "PP", 1, 0);
        // second thread group: QC1: match against MESH and OMIM
        manager.addPipelineWorkgroup(new QC1Processor(), "QC1", qcThreadCount, 0);
        // second thread group: QC2: match against synonyms
        manager.addPipelineWorkgroup(new QC2Processor(), "QC2", qcThreadCount, 0);
        // last thread group: data loading into database
        manager.addPipelineWorkgroup(new DLProcessor(), "DL", 1, 0);

        // run everything
        manager.run();

        obsoleteOrphanedSynonyms(manager.getSession(), processingStartTime);

        // dump counter statistics
        manager.dumpCounters();

        System.out.println("-- DOID mapping DONE -- elapsed time "+Utils.formatElapsedTime(time0, System.currentTimeMillis()));

        boolean dumpTopLevelDiseases = true;
        dumpRdoTermsWithoutDoId(dumpTopLevelDiseases);

        assignDoTermsNotMatchingRdo();

        log.info("DONE!");
    }

    void indexRdoSynonyms() throws Exception {

        String normalizedSynonym;

        for(Term t: dao.getAllActiveTermDescendants(dao.getOntology("RDO").getRootTermAcc()) ) {

            normalizedSynonym = synonymManager.process(t.getTerm(), "syn");
            Set<Term> rdoTerms = rdoSynonyms.get(normalizedSynonym);
            if( rdoTerms==null ) {
                rdoTerms = new HashSet<>();
                rdoSynonyms.put(normalizedSynonym, rdoTerms);
            }
            rdoTerms.add(t);

            for(TermSynonym syn: dao.getTermSynonyms(t.getAccId())) {
                normalizedSynonym = synonymManager.process(syn.getName(), "syn");
                rdoTerms = rdoSynonyms.get(normalizedSynonym);
                if( rdoTerms==null ) {
                    rdoTerms = new HashSet<>();
                    rdoSynonyms.put(normalizedSynonym, rdoTerms);
                }
                rdoTerms.add(t);
            }
        }
    }

    void dumpRdoTermsWithoutDoId(boolean dumpTopLevelDiseases) throws Exception {

        System.out.println("analyzing RDO terms without DOID...");

        List<TermWithStats> rdoTopLevelTerms = null;
        String rdoAnchorTerm = null;

        StringBuilder header = new StringBuilder();
        header.append("|RDO acc id");
        header.append("|RDO term name");
        header.append("|MESH id(s)");
        header.append("|OMIM id(s)");
        header.append("|annot obj count");
        if( dumpTopLevelDiseases ) {
            rdoAnchorTerm = dao.getOntology("RDO").getRootTermAcc();
            rdoTopLevelTerms = dao.getActiveChildTerms(rdoAnchorTerm);

            for (TermWithStats rdoTopLevelTerm : rdoTopLevelTerms) {
                header.append("|").append(rdoTopLevelTerm.getTerm());
            }
        }
        logRdoTermsNotMatchingDo.info(header.toString());

        for( String rdoAccId: dao.getRdoTermsWithoutDoId() ) {

            // dump the rdo id and term name
            StringBuilder msg = new StringBuilder();
            msg.append("|").append(rdoAccId);
            msg.append("|").append(dao.getTerm(rdoAccId).getTerm());

            appendMeshAndOmimIds(msg, rdoAccId);

            TermStats ts = dao.getTermWithStats(rdoAccId);
            msg.append("|").append(ts.term.getAnnotObjectCountForTerm());

            if( dumpTopLevelDiseases ) {
                int[] topLevelDiseaseHits = new int[rdoTopLevelTerms.size()];
                for (Map.Entry<String, String> entry : dao.getAnchorTerms(rdoAccId, rdoAnchorTerm).entrySet()) {
                    for (int i = 0; i < rdoTopLevelTerms.size(); i++) {
                        TermWithStats term = rdoTopLevelTerms.get(i);
                        if (term.getAccId().equals(entry.getKey())) {
                            topLevelDiseaseHits[i]++;
                        }
                    }
                }
                for (int topLevelDiseaseHit : topLevelDiseaseHits) {
                    msg.append("|").append(topLevelDiseaseHit);
                }
            }
            logRdoTermsNotMatchingDo.info(msg.toString());
        }
    }


    ////////////////////////////////////////////////
    //
    // From: Tutaj, Marek
    // Sent: Friday, July 28, 2017 9:00 AM
    // To: Laulederkind, Stanley <slaulede@mcw.edu>
    // Subject: DO terms not matching RDO terms
    //
    //Stan,
    //
    //  You should be getting email titled “[REED] doTermsNotMatchingRdo.log”. At the moment, there are 3281 terms like that.
    //  So I can imagine, you have little inclination to add all of them to RDO ontology. That would be *a lot* of work needed.
    //
    // However, I can automatically assign these unmapped DO terms to higher level RDO terms. Here is the logic:
    // 1)	Examine the parent DO term(s) until a term (terms) will be found that is (are) associated with RDO term
    // 2)	The created associations will be made with the source set to ‘RDO_DO_ROUGH_MAPPER’ to differentiate it from the regular ‘RDO_DO_MAPPER’
    //
    // What do you think? That algorithm will *always* work, because eventually it will find a matching RDO term, even if it is of much higher level.

    void assignDoTermsNotMatchingRdo() throws Exception {

        long time0 = System.currentTimeMillis();
        System.out.println(RDO_DO_ROUGH_MAPPER+": assign DOIDs to RDO terms without DOID...");

        Date cutoffDate = new Date();
        List<TermSynonym> inDbSynonyms = dao.getRdoRoughMapperSynonyms(cutoffDate);
        Set<TermSynonym> incomingSynonyms = new HashSet<>();

        for( String doAccId: dao.getDoTermsUnmappedToRdo() ) {
            for( int level=1; ;level++ ){
                Collection<String> parentDoTerms = getParentTerms(doAccId, level);
                if( parentDoTerms.isEmpty() ) {
                    System.out.println("*** ERROR cannot find a matching parent DO terms for "+doAccId);
                    break;
                }
                List<String> matchingRdoTerms = dao.getRdoTermsForDoIds(parentDoTerms);
                if( !matchingRdoTerms.isEmpty() ) {
                    for( String rdoTermAcc: matchingRdoTerms ) {
                        TermSynonym tsyn = new TermSynonym();
                        tsyn.setTermAcc(rdoTermAcc);
                        tsyn.setName(doAccId);
                        tsyn.setType("alt_id");
                        tsyn.setSource(RDO_DO_ROUGH_MAPPER);
                        incomingSynonyms.add(tsyn);
                    }
                    break;
                }
            }
        }

        // dump DO terms matching high level RDO
        Logger logDoTermsMatchingHighLevelRdo = Logger.getLogger("doTermsMatchingHighLevelRdo");
        for( TermSynonym tsyn: incomingSynonyms ) {
            Term doTerm = dao.getTerm(tsyn.getName());
            Term rdoTerm = dao.getTerm(tsyn.getTermAcc());
            logDoTermsMatchingHighLevelRdo.info("|"+doTerm.getAccId()+"|"+doTerm.getTerm()+"|"+rdoTerm.getAccId()+"|"+rdoTerm.getTerm());
        }

        // match incoming synonyms versus in-db synonyms
        Collection<TermSynonym> matchingSynonyms = CollectionUtils.intersection(inDbSynonyms, incomingSynonyms);
        Collection<TermSynonym> forDeleteSynonyms = CollectionUtils.subtract(inDbSynonyms, incomingSynonyms);
        Collection<TermSynonym> forInsertSynonyms = CollectionUtils.subtract(incomingSynonyms, inDbSynonyms);
        if( !matchingSynonyms.isEmpty() ) {
            dao.updateTermSynonymLastModifiedDate(matchingSynonyms);
            System.out.println("  matching DOID synonyms: "+matchingSynonyms.size());
        }
        if( !forInsertSynonyms.isEmpty() ) {
            for( TermSynonym tsyn: forInsertSynonyms ) {
                dao.insertTermSynonym(tsyn, RDO_DO_ROUGH_MAPPER);
            }
            System.out.println("  inserted DOID synonyms: "+forInsertSynonyms.size());
        }
        if( !forDeleteSynonyms.isEmpty() ) {
            int deleted = dao.deleteTermSynonyms(forDeleteSynonyms);
            System.out.println("  deleted DOID synonyms: "+deleted);
        }

        System.out.println(RDO_DO_ROUGH_MAPPER+": OK! elapsed "+Utils.formatElapsedTime(time0, System.currentTimeMillis()));
    }

    Collection<String> getParentTerms(String termAcc, int parentLevel) throws Exception {

        Collection<String> parentTermAccs = dao.getActiveParentTerms(termAcc);
        while (--parentLevel>0 ) {
            if( parentTermAccs.isEmpty() ) {
                break;
            }
            Set<String> parentTermsLevelUp = new HashSet<>();
            for( String acc: parentTermAccs ) {
                parentTermsLevelUp.addAll(dao.getActiveParentTerms(acc));
            }
            parentTermAccs = parentTermsLevelUp;
        }
        return parentTermAccs;
    }






    void appendMeshAndOmimIds(StringBuilder msg, String accId) throws Exception {

        List<TermSynonym> synonyms = dao.getTermSynonyms(accId);

        // append MESH ids
        int meshIdCount = 0;
        msg.append("|");
        for( TermSynonym tsyn: synonyms ) {
            if( tsyn.getName().startsWith("MESH:") ) {
                if( meshIdCount++ >0 ) { // separate multiple MESH ids with ','
                    msg.append(",");
                }
                msg.append(tsyn.getName());
            }
        }

        // append OMIM ids
        int omimIdCount = 0;
        msg.append("|");
        for( TermSynonym tsyn: synonyms ) {
            if( tsyn.getName().startsWith("OMIM:") ) {
                if( omimIdCount++ >0 ) { // separate multiple OMIM ids with ','
                    msg.append(",");
                }
                msg.append(tsyn.getName());
            }
        }
    }

    void obsoleteOrphanedSynonyms(PipelineSession session, Date processingStartTime) throws Exception {

        // get orphaned synonyms: 'RDO_DO_MAPPER' source
        List<TermSynonym> orphanedSynonyms = dao.getTermSynonymsModifiedBefore("RDO", "RDO_DO_MAPPER", processingStartTime);

        // NOTE: must be commented out, because it drops most importatnt RDO synonyms
        // add orphaned synonyms: 'OBO' source (loaded from CTD!)
        //orphanedSynonyms.addAll(dao.getTermSynonymsModifiedBefore("RDO", "OBO", processingStartTime));

        session.incrementCounter("SYNONYMS_ORPHANED_DELETED", orphanedSynonyms.size());

        // log orphaned synonyms
        for( TermSynonym tsyn: orphanedSynonyms ) {
            log.info("DELETE "+tsyn.dump("|"));
        }

        // delete orphaned synonyms
        dao.deleteTermSynonyms(orphanedSynonyms);
    }

    boolean isDescendantOf(TermSynonym syn1, TermSynonym syn2) throws Exception {
        return dao.isDescendantOf(syn1.getTermAcc(), syn2.getTermAcc());
    }

    private class PreProcessor extends RecordPreprocessor {
        @Override
        public void process() throws Exception {

            Ontology doOntology = dao.getOntology("DO");
            List<Term> doTerms = dao.getAllActiveTermDescendants(doOntology.getRootTermAcc());
            Collections.shuffle(doTerms);
            for (Term t : doTerms) {
                DoIdRecord rec = new DoIdRecord();
                rec.setRecNo(getNextRecNo());
                rec.doTerm = t;
                getSession().putRecordToFirstQueue(rec);
            }
        }

        int recno = 0;
        private synchronized int getNextRecNo() {
            return ++recno;
        }
    }

    private class QC1Processor extends RecordProcessor {
        @Override
        public void process(PipelineRecord pipelineRecord) throws Exception {
            DoIdRecord rec = (DoIdRecord) pipelineRecord;

            // look for manual synonyms
            List<TermSynonym> doidSynonyms = dao.getActiveSynonymsByName("RDO", rec.doTerm.getAccId());
            for( TermSynonym syn: doidSynonyms ) {
                if( syn.getSource().equals("RGD") ) {
                    addSynonym(rec, syn.getTermAcc(), rec.doTerm.getAccId(), "RGD");
                    rec.manualSynonymCount++;
                }
            }

            if( rec.rdoIncomingSynonyms.isEmpty() ) {
                // always add synonyms with OBO source to the incoming synonyms
                for( TermSynonym syn: doidSynonyms ) {
                    if( syn.getSource().equals("OBO") ) {
                        addSynonym(rec, syn.getTermAcc(), rec.doTerm.getAccId(), "OBO");
                        rec.ctdSynonymCount++;
                    }
                }
                // examine XREFS for the current DO term
                for (TermXRef xref : dao.getTermXRefs(rec.doTerm.getAccId())) {
                    String id;
                    String xrefType = Utils.NVL(xref.getXrefType(), "");
                    switch (xrefType) {
                        case "MSH":
                        case "MESH":
                            id = "MESH:" + xref.getXrefValue();
                            break;
                        case "OMIM":
                            id = "OMIM:" + xref.getXrefValue();
                            break;
                        default:
                            continue;
                    }

                    // do we have a match in RDO ontology by this MESH/OMIM id?
                    List<Term> rdoTerms = dao.getRdoTermsBySynonym(id);
                    for (Term rdoTerm : rdoTerms) {
                        addSynonym(rec, rdoTerm.getAccId(), id, "RDO_DO_MAPPER");
                    }
                }
            }

            getSession().incrementCounter("REDUNDANT_SYNONYMS_REMOVED_Q1",
                    rec.removeRedundantSynonymsToRdoChildTerms());

            // validate incoming synonyms against RGD
            for( TermSynonym tsyn: rec.rdoIncomingSynonyms ) {
                List<TermSynonym> inRgdSynonyms = dao.getTermSynonyms(tsyn.getTermAcc());
                int match = inRgdSynonyms.indexOf(tsyn);
                if( match>=0 ) {
                    rec.rdoMatchingSynonyms.add(inRgdSynonyms.get(match));
                } else {
                    rec.rdoNewSynonyms.add(tsyn);
                }
            }
        }

        void addSynonym(DoIdRecord rec, String termAcc, String xref, String source) throws Exception {
            TermSynonym tsyn = new TermSynonym();
            tsyn.setType("alt_id");
            tsyn.setTermAcc(termAcc);
            tsyn.setName(rec.doTerm.getAccId());
            tsyn.setSource(source);
            tsyn.setDbXrefs(xref);
            rec.addSynonym(tsyn);
        }
    }

    private class QC2Processor extends RecordProcessor {
        @Override
        public void process(PipelineRecord pipelineRecord) throws Exception {
            DoIdRecord rec = (DoIdRecord) pipelineRecord;

            // skip from QC, if it already had some OMIM/MESH matches
            if( !rec.rdoIncomingSynonyms.isEmpty() ) {
                getSession().incrementCounter("DO_TERMS_MATCH_BY_MESH_OMIM", 1);
                return;
            }

            // normalize DO synonyms and DO term name
            qcSynonym(rec, rec.doTerm.getTerm());
            List<TermSynonym> synonymsForDo = dao.getTermSynonyms(rec.doTerm.getAccId());
            for(TermSynonym syn: synonymsForDo) {
                qcSynonym(rec, syn.getName());
            }

            getSession().incrementCounter("REDUNDANT_SYNONYMS_REMOVED_Q2",
                rec.removeRedundantSynonymsToRdoChildTerms() );

            if( !rec.rdoIncomingSynonyms.isEmpty() ) {
                // validate incoming synonyms against RGD
                for (TermSynonym tsyn : rec.rdoIncomingSynonyms) {
                    List<TermSynonym> inRgdSynonyms = dao.getTermSynonyms(tsyn.getTermAcc());
                    int match = inRgdSynonyms.indexOf(tsyn);
                    if (match >= 0) {
                        rec.rdoMatchingSynonyms.add(inRgdSynonyms.get(match));
                    } else {
                        rec.rdoNewSynonyms.add(tsyn);
                    }
                }
                getSession().incrementCounter("DO_TERMS_MATCH_BY_SYNONYM", 1);
            } else {
                getSession().incrementCounter("DO_TERMS_NO_MATCH", 1);

                logDoTermsNotMatchingRdo.info(generateDoLineForLog(rec));
            }
        }

        void qcSynonym(DoIdRecord rec, String synonym) throws Exception {
            String normalizedSynonym = synonymManager.process(synonym, "syn");
            Collection<Term> matchingSynonyms = rdoSynonyms.get(normalizedSynonym);
            if( matchingSynonyms!=null ) {
                for (Term rdoTerm : matchingSynonyms) {
                    TermSynonym tsyn = new TermSynonym();
                    tsyn.setType("alt_id");
                    tsyn.setTermAcc(rdoTerm.getAccId());
                    tsyn.setName(rec.doTerm.getAccId());
                    tsyn.setSource("RDO_DO_MAPPER");
                    tsyn.setDbXrefs("SYNONYM:" + synonym);
                    rec.addSynonym(tsyn);
                }
            }
        }
    }

    private class DLProcessor extends RecordProcessor {

        @Override
        public void onInit() throws Exception {
            super.onInit();

            String header = "|DOID" +
                    "|DO term name";
            for (TermWithStats rdoTopLevelTerm : doTopLevelTerms) {
                header += "|"+rdoTopLevelTerm.getTerm();
            }
            header +=
                    "|RDO acc id" +
                    "|RDO term name" +
                    "|RDO acc id" +
                    "|RDO term name" +
                    "|RDO acc id" +
                    "|RDO term name";
            logDoTermsMatchingRdo.info(header);
        }

        @Override
        public void process(PipelineRecord pipelineRecord) throws Exception {
            DoIdRecord rec = (DoIdRecord) pipelineRecord;

            StringBuilder msg = new StringBuilder();

            //System.out.println(rec.getRecNo()+"."+rec.doTerm.getAccId()+" "+rec.doTerm.getTerm());

            // update existing synonyms
            if( !rec.rdoMatchingSynonyms.isEmpty() ) {
                for( TermSynonym tsyn: rec.rdoMatchingSynonyms ) {
                    msg.append("|").append(tsyn.getTermAcc());
                    msg.append("|").append(dao.getTerm(tsyn.getTermAcc()).getTerm());
                }

                dao.updateTermSynonymLastModifiedDate(rec.rdoMatchingSynonyms);
                getSession().incrementCounter("SYNONYMS_MATCHING", rec.rdoMatchingSynonyms.size());
            }

            // insert new synonyms
            if( !rec.rdoNewSynonyms.isEmpty() ) {
                for( TermSynonym tsyn: rec.rdoNewSynonyms ) {

                    msg.append("|").append(tsyn.getTermAcc());
                    msg.append("|").append(dao.getTerm(tsyn.getTermAcc()).getTerm());

                    dao.insertTermSynonym(tsyn, "RDO_DO_MAPPER");
                }
                getSession().incrementCounter("SYNONYMS_INSERTED", rec.rdoNewSynonyms.size());
            }

            if( msg.length()>0 ) {
                logDoTermsMatchingRdo.info(generateDoLineForLog(rec)+msg.toString());
            }
        }
    }

    private class DoIdRecord extends PipelineRecord {
        public Term doTerm;
        public List<TermSynonym> rdoIncomingSynonyms = new ArrayList<>();
        public List<TermSynonym> rdoMatchingSynonyms = new ArrayList<>();
        public List<TermSynonym> rdoNewSynonyms = new ArrayList<>();
        public int manualSynonymCount;
        public int ctdSynonymCount;

        void addSynonym(TermSynonym tsyn) throws Exception {

            // do not add duplicate synonyms
            if( rdoIncomingSynonyms.contains(tsyn) )
                return;

            // just add a new synonym
            rdoIncomingSynonyms.add(tsyn);
        }

        int removeRedundantSynonymsToRdoChildTerms() throws Exception {
            int synonymsRemoved = 0;

            while( !_removeRedundantSynonymsToRdoChildTerms() ) {
                synonymsRemoved++;
            }
            return synonymsRemoved;
        }

        // RULE 1: for ony 2 synonyms, if syn1 is-a syn2
        //   then only syn2 is valid
        // (i.e. from parent and child terms, only parent term synonyms are valid,
        // f.e. if there are synonyms to [Skin Diseases] and [Skin Diseases Genetic],
        //  only synonym to [Skin Diseases] is valid)

        boolean _removeRedundantSynonymsToRdoChildTerms() throws Exception {

            Iterator<TermSynonym> it = rdoIncomingSynonyms.iterator();
            while( it.hasNext() ) {
                TermSynonym syn1 = it.next();

                // if term1 synonym is a child of other synonym, it must be removed from the list
                for( TermSynonym syn2: rdoIncomingSynonyms ) {
                    if( isDescendantOf(syn1, syn2) ) {
                        it.remove();
                        return false;
                    }
                }
            }
            return true;
        }
    }

    String generateDoLineForLog(DoIdRecord rec) throws Exception {
        // dump the do id and term name
        StringBuilder msg = new StringBuilder();
        msg.append("|").append(rec.doTerm.getAccId());
        msg.append("|").append(rec.doTerm.getTerm());

        // dump top level diseases
        int[] topLevelDiseaseHits = new int[doTopLevelTerms.size()];
        for (Map.Entry<String, String> entry : dao.getAnchorTerms(rec.doTerm.getAccId(), doAnchorTerm).entrySet()) {
            for (int i = 0; i < doTopLevelTerms.size(); i++) {
                TermWithStats term = doTopLevelTerms.get(i);
                if (term.getAccId().equals(entry.getKey())) {
                    topLevelDiseaseHits[i]++;
                }
            }
        }
        for (int topLevelDiseaseHit : topLevelDiseaseHits) {
            msg.append("|").append(topLevelDiseaseHit);
        }
        return msg.toString();
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getVersion() {
        return version;
    }
     */
}
