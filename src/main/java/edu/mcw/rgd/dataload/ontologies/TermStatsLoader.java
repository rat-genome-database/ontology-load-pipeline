package edu.mcw.rgd.dataload.ontologies;

import edu.mcw.rgd.dao.impl.PhenominerDAO;
import edu.mcw.rgd.datamodel.RgdId;
import edu.mcw.rgd.datamodel.SpeciesType;
import edu.mcw.rgd.pipelines.*;
import edu.mcw.rgd.process.Utils;
import org.apache.log4j.Logger;

import java.util.*;

/**
 * @author mtutaj
 * Date: Apr 19, 2011
 * <p>
 * populates/updates ONT_TERM_STATS table to be used by ontology report pages and GViewer;
 * if filter is NOT NULL, then GViewer XML data is NOT updated for performance reason
 */
public class TermStatsLoader {
    private OntologyDAO dao;
    private Map<String,String> ontPrefixes;
    private String filter = "";

    static int[] phenoSpeciesTypeKeys = new int[]{SpeciesType.RAT, SpeciesType.CHINCHILLA};

    private final Logger logger = Logger.getLogger("stats");

    static int[] speciesTypeKeys = { // processed species
            SpeciesType.HUMAN,
            SpeciesType.MOUSE,
            SpeciesType.RAT,
            SpeciesType.DOG,
            SpeciesType.BONOBO,
            SpeciesType.CHINCHILLA,
            SpeciesType.SQUIRREL,
    };
    static int[] objectKeys = { // processed objects
            RgdId.OBJECT_KEY_GENES,
            RgdId.OBJECT_KEY_QTLS,
            RgdId.OBJECT_KEY_STRAINS,
            RgdId.OBJECT_KEY_VARIANTS,
    };

    public String getFilter() {
        return filter;
    }

    public void setFilter(String filter) {
        this.filter = filter;
    }

    public OntologyDAO getDao() {
        return dao;
    }

    public void setDao(OntologyDAO dao) {
        this.dao = dao;
    }

    /**
     * compute statistics for terms with accession ids having specific prefixes
     * @param ontPrefixes set of allowed prefixes for ont term accession ids
     * @throws Exception
     */
    public void run(final Map<String,String> ontPrefixes, int qcThreadCount) throws Exception {

        if( getFilter()!=null ) {
            TermStatsLoaderWithFilter.run(ontPrefixes.values(), filter, getDao());
            return;
        }

        long time0 = System.currentTimeMillis();

        this.ontPrefixes = ontPrefixes;

        PipelineManager manager = new PipelineManager();
        // first thread group: read all ontology terms

        PreProcessor pp = new PreProcessor();
        manager.addPipelineWorkgroup(pp, "PP", 1, 0);
        // second thread group: compute term statistics in multiple threads
        manager.addPipelineWorkgroup(new QCProcessor(), "QC", qcThreadCount, 0);
        // last thread group: save term statistics into database in 1 thread
        manager.addPipelineWorkgroup(new DLProcessor(), "DL", 1, 0);

        // run everything
        manager.run();

        obsoleteOrphanedTerms(manager.getSession());

        // dump counter statistics
        manager.dumpCounters();

        System.out.println("-- computing term stats -- DONE -- elapsed "+ Utils.formatElapsedTime(time0, System.currentTimeMillis()));

        logger.info("DONE!");
    }

    void obsoleteOrphanedTerms(PipelineSession session) throws Exception {
        // terms that once were part of ontology dag tree, but are no longer
        int obsoleteTermCount = 0;
        for (String ontPrefix: ontPrefixes.keySet()) {
            if( MalformedOboFiles.getInstance().isWellFormed(ontPrefix) ) {
                int obsoleteCount = getDao().obsoleteOrphanedTerms(ontPrefix);
                session.incrementCounter("ORPHANED_TERMS_MADE_OBSOLETE_"+ontPrefix, obsoleteCount);
                obsoleteTermCount += obsoleteCount;
            }
        }
        session.incrementCounter("ORPHANED_TERMS_MADE_OBSOLETE", obsoleteTermCount);
    }

    // compute nr of annotations for every term
    class PreProcessor extends RecordPreprocessor {

        int recno = 0;

        private synchronized int getNextRecNo() {
            return ++recno;
        }

        @Override
        public void process() throws Exception {

            // for every ontology, load list of term acc ids
            List<List<String>> accIdLists = new ArrayList<>();
            for( String ontPrefix: ontPrefixes.values() ) {
                List<String> accIds = dao.getAllTermAccIds(ontPrefix);
                accIdLists.add(accIds);
                System.out.println("TERM COUNT for "+ontPrefix+" is "+accIdLists.get(accIdLists.size()-1).size());
            }

            // add term acc ids to processing queue in round robin fashion,
            // picking term acc ids sequentially from the lists of term acc ids;
            // that will ensure that top-level terms are most likely to be processed first
            // so total processing time will be minimized
            int cnt = 0;
            while( !accIdLists.isEmpty() ) {
                Iterator<List<String>> it = accIdLists.iterator();
                while( it.hasNext() ) {
                    List<String> accIdList = it.next();
                    if( accIdList.isEmpty() )
                        it.remove();
                    else {
                        String termAccId = accIdList.remove(0);
                        PRecord rec = new PRecord();
                        rec.setRecNo(getNextRecNo());
                        rec.stats.setTermAccId(termAccId);
                        //rec.stats.setFilter("RDO:0005134");
                        rec.stats.setFilter(getFilter());

                        getSession().putRecordToFirstQueue(rec);
                        cnt++;
                    }
                }
            }
            logger.debug("loaded ontologies, term count="+cnt);
        }
    }

    class QCProcessor extends RecordProcessor {

        PhenominerDAO phenominerDAO = new PhenominerDAO();
        int[] primaryMapKey = new int[8];

        public QCProcessor() throws Exception {
            primaryMapKey[SpeciesType.HUMAN] = dao.getPrimaryRefAssemblyMapKey(SpeciesType.HUMAN);
            primaryMapKey[SpeciesType.MOUSE] = dao.getPrimaryRefAssemblyMapKey(SpeciesType.MOUSE);
            primaryMapKey[SpeciesType.RAT] = dao.getPrimaryRefAssemblyMapKey(SpeciesType.RAT);
            primaryMapKey[SpeciesType.CHINCHILLA] = dao.getPrimaryRefAssemblyMapKey(SpeciesType.CHINCHILLA);
            primaryMapKey[SpeciesType.BONOBO] = dao.getPrimaryRefAssemblyMapKey(SpeciesType.BONOBO);
            primaryMapKey[SpeciesType.DOG] = dao.getPrimaryRefAssemblyMapKey(SpeciesType.DOG);
            primaryMapKey[SpeciesType.SQUIRREL] = dao.getPrimaryRefAssemblyMapKey(SpeciesType.SQUIRREL);
        }

        public void process(PipelineRecord r) throws Exception {

            PRecord rec = (PRecord) r;
            String accId = rec.stats.getTermAccId();

            long time0 = System.currentTimeMillis();
            logger.debug(rec.getRecNo() + ". " + Thread.currentThread().getName() + " " + accId + " START");

            if (getFilter() == null) {

                //compute count for
                // compute child/parent counts for given term
                int childTermCount = dao.getDescendantCount(accId);
                rec.stats.term.addStat("child_term_count", 0, 0, 0, childTermCount, null);

                int parentTermCount = dao.getAncestorCount(accId);
                rec.stats.term.addStat("parent_term_count", 0, 0, 0, parentTermCount, null);

                // get term stats from incoming data:
                //
                // 'phenominer' ontologies: experiment records are their annotations;
                //    count of experiment records will be used for them

                getPhenominerAnnots(rec.stats);
                // get diagram counts for pathway terms
                getPathwayStats(rec.stats);

                // then handle the regular annotations

            }

            for( int speciesTypeKey: speciesTypeKeys ) {
                computeAnnotatedObjectCount(rec.stats, speciesTypeKey);
            }
            //computeAnnotatedObjectCountForAllObjectsAndSpecies(rec.stats);


            TermStats statsInRgd = dao.getTermWithStats(accId, getFilter());
            if (!rec.stats.equals(statsInRgd)) {
                rec.setFlag("LOAD");
            }

            long time1 = System.currentTimeMillis();
            logger.debug(rec.getRecNo() + ". " + Thread.currentThread().getName() + "  " + accId + " STOP " + (time1 - time0) + " ms");
        }

        void computeAnnotatedObjectCount(TermStats stats, int speciesTypeKey) throws Exception {

            for( int objectKey: objectKeys ) {
                computeAnnotatedObjectCount(stats, speciesTypeKey, objectKey);
            }
            //computeAnnotatedObjectCount(stats, speciesTypeKey, 0);
        }

        void computeAnnotatedObjectCount(TermStats stats, int speciesTypeKey, int objectKey) throws Exception {

            int count = dao.getAnnotatedObjectCount(stats.getTermAccId(), objectKey, speciesTypeKey, true, filter);

            stats.term.addStat("annotated_object_count", speciesTypeKey, objectKey, 1, count, filter);

            // optimization:
            // if annotated-object-count for term-with-children was 0,
            // then for term-only it must be zero, too
            if (count != 0) {
                count = dao.getAnnotatedObjectCount(stats.getTermAccId(), objectKey, speciesTypeKey, false, filter);
            }
            stats.term.addStat("annotated_object_count", speciesTypeKey, objectKey, 0, count, filter);
        }

        void getPhenominerAnnots(TermStats stats) throws Exception {

            String accId = stats.getTermAccId();

            // process only terms belonging to one of ontologies used in phenominer tool
            if( !accId.startsWith("RS:") &&
                    !accId.startsWith("CS:") &&
                    !accId.startsWith("CMO:") &&
                    !accId.startsWith("MMO:") &&
                    !accId.startsWith("XCO:") ) {
                return;
            }

            // currently we support only RAT and CHINCHILLA
            for( int speciesTypeKey: phenoSpeciesTypeKeys ) {
                getPhenominerAnnots(stats, speciesTypeKey);
            }
        }

        void getPhenominerAnnots(TermStats stats, int speciesTypeKey) throws Exception {

            String accId = stats.getTermAccId();

            //String annotsInXml = null;
            int annotCountForTerm = phenominerDAO.getRecordCountForTerm(accId, speciesTypeKey);
            if( annotCountForTerm>0 ) {
                stats.term.addStat("pheno_annotation_count", speciesTypeKey, 0, 0, annotCountForTerm, null);
            }

            // NOTE: ontology annotation page is never showing phenominer annotations to child terms
            //    (per 'improved presentation of related phenotype data' in Mid June 2012)
            // but we still should show annot counts with children
            // however, for GViewer, we should not display annotations to child terms

            int annotCountWithChildren = phenominerDAO.getRecordCountForTermAndDescendants(accId, speciesTypeKey);
            if( annotCountWithChildren>0 ) {
                stats.term.addStat("pheno_annotation_count", speciesTypeKey, 0, 1, annotCountWithChildren, null);
            }

            // RS annotation counts for males and females
            if( accId.startsWith("RS:") ) {
                annotCountForTerm = phenominerDAO.getRecordCountForStrainTerm(accId, "male", speciesTypeKey);
                if( annotCountForTerm>0 ) {
                    stats.term.addStat("pheno_male_annotation_count", speciesTypeKey, 0, 0, annotCountForTerm, null);
                }
                annotCountForTerm = phenominerDAO.getRecordCountForStrainTerm(accId, "female", speciesTypeKey);
                if( annotCountForTerm>0 ) {
                    stats.term.addStat("pheno_female_annotation_count", speciesTypeKey, 0, 0, annotCountForTerm, null);
                }

                annotCountWithChildren = phenominerDAO.getRecordCountForStrainTermAndDescendants(accId, "male", speciesTypeKey);
                if( annotCountWithChildren>0 ) {
                    stats.term.addStat("pheno_male_annotation_count", speciesTypeKey, 0, 1, annotCountWithChildren, null);
                }
                annotCountWithChildren = phenominerDAO.getRecordCountForStrainTermAndDescendants(accId, "female", speciesTypeKey);
                if( annotCountWithChildren>0 ) {
                    stats.term.addStat("pheno_female_annotation_count", speciesTypeKey, 0, 1, annotCountWithChildren, null);
                }
            }
        }

        void getPathwayStats(TermStats stats) throws Exception {

            String accId = stats.getTermAccId();

            //remove
            // process only terms belonging to pathway ontology
            if( !accId.startsWith("PW:") ) {
                return;
            }

            // is there a diagram for this pathway?
            stats.term.addStat("diagram_count", 0, 0, 0, dao.getDiagramCount(accId, false), null);
            // is there a diagram for this pathway and child pathways?
            stats.term.addStat("diagram_count", 0, 0, 1, dao.getDiagramCount(accId, true),null);
        }
    }

    class DLProcessor extends RecordProcessor {
        public void process(PipelineRecord r) throws Exception {
            PRecord rec = (PRecord) r;

            if( rec.isFlagSet("LOAD") ) {
                logger.debug(Thread.currentThread().getName()+"  "+rec.stats.getTermAccId()+" LOAD");

                dao.updateTermStats(rec.stats);

                if( !rec.stats.statsToBeAdded.isEmpty() ) {
                    getSession().incrementCounter("STATS_INSERTED", rec.stats.statsToBeAdded.size());
                    getSession().incrementCounter("TERMS_WITH_STATS_INSERTED", 1);
                }
                if( !rec.stats.statsToBeUpdated.isEmpty() ) {
                    getSession().incrementCounter("STATS_UPDATED", rec.stats.statsToBeUpdated.size());
                    getSession().incrementCounter("TERMS_WITH_STATS_UPDATED", 1);
                }
                if( !rec.stats.statsToBeDeleted.isEmpty() ) {
                    getSession().incrementCounter("STATS_DELETED", rec.stats.statsToBeDeleted.size());
                    getSession().incrementCounter("TERMS_WITH_STATS_DELETED", 1);
                }
            }
            else {
                logger.debug(Thread.currentThread().getName()+"  "+rec.stats.getTermAccId()+" MATCH");

                getSession().incrementCounter("TERMS_WITH_STATS_MATCHED", 1);
            }
        }
    }

    // shared structure to be passed between processing queues
    class PRecord extends PipelineRecord {
        public TermStats stats = new TermStats();
    }
}
