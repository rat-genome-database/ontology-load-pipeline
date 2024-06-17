package edu.mcw.rgd.dataload.ontologies;

import edu.mcw.rgd.dao.impl.PhenominerDAO;
import edu.mcw.rgd.datamodel.RgdId;
import edu.mcw.rgd.datamodel.SpeciesType;
import edu.mcw.rgd.process.CounterPool;
import edu.mcw.rgd.process.Utils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.*;
import java.util.concurrent.ForkJoinPool;

/**
 * @author mtutaj
 * Date: Apr 19, 2011
 * <p>
 * populates/updates ONT_TERM_STATS2 table to be used by ontology report pages
 */
public class TermStatsLoader {
    private OntologyDAO dao;
    private PhenominerDAO phenominerDAO = new PhenominerDAO();
    private String filter = "";

    private int maxThreadCount;

    static int[] phenoSpeciesTypeKeys = new int[]{SpeciesType.RAT, SpeciesType.CHINCHILLA};

    private final Logger logger = LogManager.getLogger("stats");

    public static int[] PROCESSED_OBJECT_KEYS = { // processed objects
            RgdId.OBJECT_KEY_GENES,
            RgdId.OBJECT_KEY_QTLS,
            RgdId.OBJECT_KEY_STRAINS,
            RgdId.OBJECT_KEY_VARIANTS,
            RgdId.OBJECT_KEY_CELL_LINES,
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
    public void run(Map<String,String> ontPrefixes) throws Exception {

        String lockName = Utils.concatenate(ontPrefixes.values(), "-");
        int maxLockAttempts = 60*5; // 5 hours -- gave up after 5 hours
        long lockSleepInMs = 1000*60; // 1 min interval to try acquire the lock
        try( FileSystemLock fileSystemLock = new FileSystemLock(maxLockAttempts, lockSleepInMs, lockName) ) {
            fileSystemLock.acquire(logger);

            if (getFilter() != null) {
                TermStatsLoaderWithFilter.run(ontPrefixes.values(), filter, getDao());
                fileSystemLock.release(logger);
                return;
            }

            long time0 = System.currentTimeMillis();

            // load species type keys to process (exclude non-public species like yeast, zebrafish etc)
            List<Integer> speciesTypeKeys = new ArrayList<>();
            for (int sp : SpeciesType.getSpeciesTypeKeys()) {
                if (SpeciesType.isSearchable(sp)) {
                    speciesTypeKeys.add(sp);
                }
            }

            CounterPool counters = new CounterPool();

            List<PRecord> records = loadRecordsToProcess(ontPrefixes);

            /** original code -- use all available cores
             *
             records.parallelStream().forEach(rec -> {
             try {
             qc(rec, counters, speciesTypeKeys);
             } catch (Exception e) {
             throw new RuntimeException(e);
             }
             });*/

            /// new code: do not use more than specified number of threads
            {
                ForkJoinPool customThreadPool = new ForkJoinPool(getMaxThreadCount());
                customThreadPool.submit(() -> records.parallelStream().forEach(rec -> {
                    try {
                        qc(rec, counters, speciesTypeKeys);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                })).get();
                customThreadPool.shutdown();
            }

            // dump counter statistics
            System.out.println(counters.dumpAlphabetically());

            System.out.println("-- computing term stats -- DONE -- elapsed " + Utils.formatElapsedTime(time0, System.currentTimeMillis()));

            fileSystemLock.release(logger);

            logger.info("DONE!");
        }
    }

    List<PRecord> loadRecordsToProcess(Map<String,String> ontPrefixes) throws Exception {

        List<PRecord> results = new ArrayList<>();

        // for every ontology, load list of term acc ids
        for( String ontPrefix: ontPrefixes.values() ) {
            List<String> accIds = dao.getAllTermAccIds(ontPrefix);
            System.out.println("TERM COUNT for "+ontPrefix+" is "+accIds.size());

            for( String accId: accIds ) {
                PRecord rec = new PRecord();
                rec.stats.setTermAccId(accId);
                rec.stats.setFilter(getFilter());

                results.add(rec);
            }
        }

        // randomize term order for more even load in multi-thread processing
        Collections.shuffle(results);

        logger.debug("loaded ontologies, term count="+results.size());
        return results;
    }

    void qc(PRecord rec, CounterPool counters, List<Integer> speciesTypeKeys) throws Exception {

        String accId = rec.stats.getTermAccId();

        long time0 = System.currentTimeMillis();
        logger.debug(Thread.currentThread().getName() + " " + accId + " START");

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
            logger.debug(Thread.currentThread().getName()+"  "+rec.stats.getTermAccId()+" LOAD");

            dao.updateTermStats(rec.stats);

            if( !rec.stats.statsToBeAdded.isEmpty() ) {
                counters.add("STATS_INSERTED", rec.stats.statsToBeAdded.size());
                counters.increment("TERMS_WITH_STATS_INSERTED");
            }
            if( !rec.stats.statsToBeUpdated.isEmpty() ) {
                counters.add("STATS_UPDATED", rec.stats.statsToBeUpdated.size());
                counters.increment("TERMS_WITH_STATS_UPDATED");
            }
            if( !rec.stats.statsToBeDeleted.isEmpty() ) {
                counters.add("STATS_DELETED", rec.stats.statsToBeDeleted.size());
                counters.increment("TERMS_WITH_STATS_DELETED");
            }
        }
        else {
            logger.debug(Thread.currentThread().getName()+"  "+rec.stats.getTermAccId()+" MATCH");

            counters.increment("TERMS_WITH_STATS_MATCHED");
        }

        int termsProcessed = counters.increment("TERMS_PROCESSED");
        long time1 = System.currentTimeMillis();
        logger.debug(termsProcessed+". "+Thread.currentThread().getName() + "  " + accId + " STOP " + (time1 - time0) + " ms");
    }

    void computeAnnotatedObjectCount(TermStats stats, int speciesTypeKey) throws Exception {

        for( int objectKey: PROCESSED_OBJECT_KEYS ) {
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


    // shared structure to be passed between processing queues
    class PRecord {
        public TermStats stats = new TermStats();
    }

    public int getMaxThreadCount() {
        return maxThreadCount;
    }

    public void setMaxThreadCount(int maxThreadCount) {
        this.maxThreadCount = maxThreadCount;
    }
}