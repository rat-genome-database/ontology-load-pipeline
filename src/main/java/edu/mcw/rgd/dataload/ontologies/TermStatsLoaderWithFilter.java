package edu.mcw.rgd.dataload.ontologies;

import edu.mcw.rgd.dao.spring.StringMapQuery;
import edu.mcw.rgd.datamodel.SpeciesType;
import edu.mcw.rgd.process.Utils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Created by mtutaj on 9/11/2017.
 */
public class TermStatsLoaderWithFilter {

    private OntologyDAO dao;

    public static void main(String[] args) throws Exception {

        String ontPrefix = "PW";
        String filter = "RDO:0003591";

        List<String> ontPrefixes = new ArrayList<>();
        ontPrefixes.add(ontPrefix);
        run(ontPrefixes, filter, new OntologyDAO());
    }

    public static void run(Collection<String> ontPrefixes, String filter, OntologyDAO odao) throws Exception {

        TermStatsLoaderWithFilter loader = new TermStatsLoaderWithFilter(odao);
        for( String ontPrefix: ontPrefixes ) {
            String ont = ontPrefix.equals("*") ? "DOID:" : ontPrefix;
            loader.run(ont, filter);
        }
    }

    public TermStatsLoaderWithFilter(OntologyDAO dao) {
        this.dao = dao;
    }

    void run(String ontPrefix, String filter) throws Exception {

        final Logger log = LogManager.getLogger("stats");
        log.info("processing "+ontPrefix+" with filter "+filter);

        long time0 = System.currentTimeMillis();

        // load species type keys to process (exclude non-public species like yeast, zebrafish etc)
        List<Integer> speciesTypeKeys = new ArrayList<>();
        for( int sp: SpeciesType.getSpeciesTypeKeys() ) {
            if( SpeciesType.isSearchable(sp) ) {
                speciesTypeKeys.add(sp);
            }
        }

        // build dag
        Map<String, TermData> dag = new ConcurrentHashMap<>();
        List<StringMapQuery.MapPair> dagRelations = loadDagRelations(ontPrefix, log);
        for (StringMapQuery.MapPair pair : dagRelations) {
            TermData parent = dag.get(pair.keyValue);
            if (parent == null) {
                parent = new TermData(pair.keyValue, filter);
                dag.put(pair.keyValue, parent);
            }

            TermData child = dag.get(pair.stringValue);
            if (child == null) {
                child = new TermData(pair.stringValue, filter);
                dag.put(pair.stringValue, child);
            }

            parent.addChild(child);
        }
        System.out.println(ontPrefix+" term count "+dag.size());
        log.info("  dag graph built");

        // compute stats for species and object
        for( int speciesTypeKey: speciesTypeKeys ) {
            for( int objectKey: TermStatsLoader.PROCESSED_OBJECT_KEYS ) {
                run(filter, dag, dao, speciesTypeKey, objectKey, log);
            }
        }

        // dump non zero stats
        qcAndLoadStats(dag, ontPrefix, filter, log);

        String msg = "=== "+ontPrefix+" OK === elapsed "+ Utils.formatElapsedTime(time0, System.currentTimeMillis());
        log.info(msg);
        System.out.println(msg);
    }

    List<StringMapQuery.MapPair> loadDagRelations(String ontPrefix, Logger log) throws Exception {

        List<StringMapQuery.MapPair> dagRelations = null;
        Exception lastException = null;
        int attempt;
        for( attempt=0; attempt<10; attempt++ ) {
            try {
                dagRelations = dao.getDagForOntologyPrefix(ontPrefix);
                break;
            } catch (Exception e) {
                e.printStackTrace();
                String m = "*** EXCEPTION *** attempt "+(attempt+1)+" to resume loading dag for " + ontPrefix;
                log.warn(m);
                System.out.println(m);
                lastException = e;
            }
        }
        if( attempt==10 ) {
            String m = "*** EXCEPTION *** dag loading broken for "+ontPrefix;
            log.error(m);
            System.err.println(m);
            throw lastException;
        }

        log.info("  dag relations loaded for "+ontPrefix);
        Collections.shuffle(dagRelations);
        return dagRelations;
    }

    final static int STATS_INSERTED = 0;
    final static int STATS_UPDATED = 1;
    final static int STATS_DELETED = 2;
    final static int TERMS_WITH_UP_TO_DATE_STATS = 3;

    void qcAndLoadStats(Map<String, TermData> dag, String ontPrefix, String filter, Logger log) {
        log.info("  qc and load the stats");
        final AtomicInteger[] counters = new AtomicInteger[4];
        for( int i=0; i<4; i++ ) {
            counters[i] = new AtomicInteger(0);
        }

        dag.values().parallelStream().forEach(td -> {
            try {
                TermStats tsInRgd = dao.getTermWithStats(td.getTermAcc(), filter);
                //determine which stats match, which should be deleted, inserted and updated
                if (td.stats.equals(tsInRgd)) {
                    counters[TERMS_WITH_UP_TO_DATE_STATS].incrementAndGet();
                } else {
                    dao.updateTermStats(td.stats);
                    int count = td.stats.statsToBeAdded.size();
                    if( count!=0 ) {
                        counters[STATS_INSERTED].getAndAdd(count);
                    }
                    count = td.stats.statsToBeUpdated.size();
                    if( count!=0 ) {
                        counters[STATS_UPDATED].getAndAdd(count);
                    }
                    count = td.stats.statsToBeDeleted.size();
                    if( count!=0 ) {
                        counters[STATS_DELETED].getAndAdd(count);
                    }
                }
            } catch(Exception e) {
                e.printStackTrace();
                String m = "*** EXCEPTION *** broken processing for "+ontPrefix+" with filter "+filter;
                log.error(m);
                System.out.println(m);
                throw new RuntimeException(e);
            }
        });
        int count = counters[STATS_INSERTED].get();
        if( count!=0 ) {
            System.out.println("    stats inserted: "+count);
        }
        count = counters[STATS_UPDATED].get();
        if( count!=0 ) {
            System.out.println("    stats updated: "+count);
        }
        count = counters[STATS_DELETED].get();
        if( count!=0 ) {
            System.out.println("    stats deleted: "+count);
        }
        System.out.println("    terms with up-to-date stats: "+counters[TERMS_WITH_UP_TO_DATE_STATS].get());
    }

    void run(String filter, Map<String, TermData> dag, OntologyDAO dao, int speciesTypeKey, int objectKey, Logger log) throws Exception {

        log.debug("  INIT species="+speciesTypeKey+" object="+objectKey);

        // rgd ids for given filter, species and object key
        Set<Integer> rgdIdsForFilter = new HashSet<>(dao.getAnnotatedObjectIds(filter, true, speciesTypeKey, objectKey));
        log.debug("  rgd ids for filter loaded");

        // clean dag
        dag.values().parallelStream().forEach(td -> {
            int attempt;
            for( attempt=0; attempt<10; attempt++ ) {
                try {
                    td.rgdIds = dao.getAnnotatedObjectIds(td.getTermAcc(), false, speciesTypeKey, objectKey);
                    break;
                } catch (Exception e) {
                    e.printStackTrace();
                    String m = "*** EXCEPTION *** attempt "+(attempt+1)+" to resume processing for species=" + speciesTypeKey + " object=" + objectKey;
                    log.warn(m);
                    System.out.println(m);
                }
            }
            if( attempt==10 ) {
                String m = "*** EXCEPTION *** processing broken for species=" + speciesTypeKey + " object=" + objectKey;
                log.error(m);
                System.err.println(m);
                throw new RuntimeException(m);
            }
            td.rgdIds.retainAll(rgdIdsForFilter);
            td.rgdIdsWithChildren = null;
        });
        log.debug("  rgd ids for ontology loaded");

        // compute for-children stats
        for (TermData td : dag.values()) {
            // collect data for children
            if (td.rgdIdsWithChildren == null) {
                td.rgdIdsWithChildren = td.getRgdIdsWithChildren();
            }
        }
        log.debug("  rgd ids for term with children computed");

        dag.values().parallelStream().forEach(td -> {
            //td.stats.term.addStat("annotated_object_count", speciesTypeKey, objectKey, 0, td.rgdIds.size(), filter);
            td.stats.term.addStat("annotated_object_count", speciesTypeKey, objectKey, 1, td.rgdIdsWithChildren.size(), filter);
        });
        log.debug("  DONE species="+speciesTypeKey+" object="+objectKey);
        //System.out.println("  DONE species="+speciesTypeKey+" object="+objectKey);
    }

    class TermData {
        TermStats stats = new TermStats();
        List<TermData> children;
        List<Integer> rgdIds;
        Collection<Integer> rgdIdsWithChildren;

        public TermData(String termAcc, String filter) {
            stats.setTermAccId(termAcc);
            stats.setFilter(filter);
        }

        String getTermAcc() {
            return stats.getTermAccId();
        }

        void addChild(TermData child) {
            if (children == null) {
                children = new ArrayList<>();
            }
            children.add(child);
        }

        Collection<Integer> getRgdIdsWithChildren() {
            // check if already precomputed
            if (rgdIdsWithChildren != null) {
                return rgdIdsWithChildren;
            }

            // if no children, it is easy
            if (children == null) {
                rgdIdsWithChildren = rgdIds;
                return rgdIdsWithChildren;
            }
            
            Set<Integer> result = new HashSet<>(rgdIds);
            for (TermData td : children) {
                result.addAll(td.getRgdIdsWithChildren());
            }
            return result;
        }
    }
}
