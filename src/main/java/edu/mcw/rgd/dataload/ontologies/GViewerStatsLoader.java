package edu.mcw.rgd.dataload.ontologies;

import edu.mcw.rgd.dao.spring.IntStringMapQuery;
import edu.mcw.rgd.datamodel.MapData;
import edu.mcw.rgd.datamodel.RgdId;
import edu.mcw.rgd.datamodel.SpeciesType;
import edu.mcw.rgd.process.CounterPool;
import edu.mcw.rgd.process.Utils;
import edu.mcw.rgd.reporting.Link;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author mtutaj
 * Date: Apr 19, 2011
 * <p>
 * populates/updates ONT_TERM_STATS table to be used by ontology report pages and GViewer;
 * if filter is NOT NULL, then GViewer XML data is NOT updated for performance reason
 * <p>
 * NOTE: code discontinued as of May 22, 2023
 */
public class GViewerStatsLoader {
    private OntologyDAO dao;
    private final Logger logger = LogManager.getLogger("gviewer_stats");
    private String version;
    private Set<String> processedOntologyPrefixes;

    // maximum number of annotations that will be used;
    // the rest will be ignored
    private int maxAnnotCountPerTerm;

    private int[] primaryMapKey = new int[4];

    public void init() throws Exception {
        primaryMapKey[SpeciesType.HUMAN] = dao.getPrimaryRefAssemblyMapKey(SpeciesType.HUMAN);
        primaryMapKey[SpeciesType.MOUSE] = dao.getPrimaryRefAssemblyMapKey(SpeciesType.MOUSE);
        primaryMapKey[SpeciesType.RAT] = dao.getPrimaryRefAssemblyMapKey(SpeciesType.RAT);
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

        long time0 = System.currentTimeMillis();

        logger.info("");
        logger.info("GVIEWER STATS LOADER");
        logger.info("   "+dao.getConnectionInfo());

        SimpleDateFormat sdt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        logger.info("   started at "+sdt.format(new Date(time0)));
        logger.info("");

        init();

        List<String> incomingTermAccs = loadIncomingTermAccs(ontPrefixes);

        Collection<TermStats> termStats = qc(incomingTermAccs);
        load(termStats);

        logger.info("GVIEWER STATS DONE -- elapsed "+ Utils.formatElapsedTime(time0, System.currentTimeMillis()));
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getVersion() {
        return version;
    }

    public void setMaxAnnotCountPerTerm(int maxAnnotCountPerTerm) {
        this.maxAnnotCountPerTerm = maxAnnotCountPerTerm;
    }

    public int getMaxAnnotCountPerTerm() {
        return maxAnnotCountPerTerm;
    }

    List<String> loadIncomingTermAccs(Map<String,String> ontPrefixes) throws Exception {

        List<String> results = new ArrayList<>();

        String processedOntPrefixes = "";
        String skippedOntPrefixes = "";

        // for every ontology, load list of term acc ids
        for( String ontPrefix: ontPrefixes.values() ) {

            if( getProcessedOntologyPrefixes().contains(ontPrefix) ) {
                List<String> accIds = dao.getAllTermAccIds(ontPrefix);
                results.addAll(accIds);
                logger.debug("GVIEWER STATS: TERM COUNT for " + ontPrefix + " is " + accIds.size());

                if( ontPrefix.equals("*") ) {
                    ontPrefix = "DOID:";
                }
                processedOntPrefixes += ontPrefix + " ";
            } else {
                logger.debug("GVIEWER STATS: TERM COUNT for " + ontPrefix + " is 0 -- not on processed list");

                skippedOntPrefixes += ontPrefix + " ";
            }
        }

        Collections.shuffle(results);
        logger.info("GVIEWER STATS: loaded ontologies, term count="+results.size());
        logger.info("    processed ontology prefixes: ["+processedOntPrefixes.trim()+"]");
        logger.info("    skipped ontology prefixes: ["+skippedOntPrefixes.trim()+"]");

        return results;
    }

    public Collection<TermStats> qc(List<String> incomingTermAccs) {

        ConcurrentHashMap<String, TermStats> results = new ConcurrentHashMap<>();

        incomingTermAccs.parallelStream().forEach( accId -> {

            try {
                TermStats stats = dao.getTermWithStats(accId);
                results.put(accId, stats);

                long time0 = System.currentTimeMillis();
                logger.debug(accId + " START  [#"+results.size()+"]");

                getAnnots(stats, SpeciesType.HUMAN);
                getAnnots(stats, SpeciesType.MOUSE);
                getAnnots(stats, SpeciesType.RAT);

                TermStats statsInRgd = dao.getGViewerStats(accId);
                if (!stats.equalsForGViewer(statsInRgd)) {
                }

                long time1 = System.currentTimeMillis();
                logger.debug(accId + " STOP " + (time1 - time0) + " ms");

            } catch(Exception e) {
                Utils.printStackTrace(e, logger);
                throw new RuntimeException(e);
            }
        });

        return results.values();
    }

    void getAnnots(TermStats stats, int speciesTypeKey) throws Exception {

        List<IntStringMapQuery.MapPair> annots;
        final boolean withVariants = false;
        if( stats.term.getAnnotObjectCountForSpecies(speciesTypeKey, withVariants)>getMaxAnnotCountPerTerm() ) {
            logger.debug("  gviewer stats skipped for "+stats.getTermAccId()+" species="+speciesTypeKey);
            annots = Collections.emptyList();
        } else{
            annots = loadAnnots(stats, speciesTypeKey);
        }

        XmlInfo xml = new XmlInfo();

        for( IntStringMapQuery.MapPair annot: annots ) {
            boolean isChildTerm = !annot.stringValue.equals(stats.getTermAccId());
            processXml(annot.keyValue, speciesTypeKey, xml, isChildTerm);
        }

        processXml(stats, speciesTypeKey, xml);
    }

    List<IntStringMapQuery.MapPair> loadAnnots(TermStats stats, int speciesTypeKey) throws Exception {
        List<IntStringMapQuery.MapPair> annots = dao.getAnnotatedRgdIds(stats.getTermAccId(), speciesTypeKey);
        return annots;
    }

    void processXml(int objRgdId, int speciesTypeKey, XmlInfo info, boolean isChildTerm) throws Exception {
        String type, color, link;
        switch(dao.getObjectKey(objRgdId)) {
            case RgdId.OBJECT_KEY_GENES: type="gene";
                color="0x79CC3D";
                link= Link.gene(objRgdId);
                break;
            case RgdId.OBJECT_KEY_QTLS: type="qtl";
                color="0xCCCCCC";
                link= Link.qtl(objRgdId);
                break;
            case RgdId.OBJECT_KEY_STRAINS: type="strain";
                color="0xBBBB0F";
                link= Link.strain(objRgdId);
                break;
            default:
                return;
        }

        List<MapData> mds = dao.getMapData(objRgdId, primaryMapKey[speciesTypeKey]);
        for (MapData md : mds) {

            String feature = "<feature>" + "<chromosome>" + md.getChromosome() + "</chromosome>" +
                    "<start>" + md.getStartPos() + "</start>" +
                    "<end>" + md.getStopPos() + "</end>" +
                    "<type>" + type + "</type>" +
                    "<label>" + encode(dao.getObjectSymbol(objRgdId)) + "</label>" +
                    "<link>" + link + "</link>" +
                    "<color>" + color + "</color>" +
                    "</feature>\n";

            if (info.featuresWithChilds < getMaxAnnotCountPerTerm()) {
                if (info.features1.add(feature)) {
                    // skip duplicate features
                    if (info.xmlWithChilds == null)
                        info.xmlWithChilds = new StringBuilder("<genome>");
                    info.xmlWithChilds.append(feature);
                    info.featuresWithChilds++;
                }
            }
            if (!isChildTerm && info.featuresForTerm < getMaxAnnotCountPerTerm()) {
                if (info.features2.add(feature)) {
                    if (info.xmlForTerm == null)
                        info.xmlForTerm = new StringBuilder("<genome>");
                    info.xmlForTerm.append(feature);
                    info.featuresForTerm++;
                }
            }
        }
    }

    void processXml(TermStats stats, int speciesTypeKey, XmlInfo info) {

        if( info.xmlForTerm!=null ) {
            info.xmlForTerm.append("</genome>\n");
            stats.setXmlForTerm(info.xmlForTerm.toString(), speciesTypeKey);
        }

        if( info.xmlWithChilds!=null ) {
            info.xmlWithChilds.append("</genome>\n");
            stats.setXmlWithChilds(info.xmlWithChilds.toString(), speciesTypeKey);
        }
    }

    String encode(String txt) {
        // utility to encode '<' and '>' in strain symbols
        if( txt.indexOf('<')>=0 ) {
            return txt.replace("<", "&lt;").replace(">", "&gt;");
        }
        return txt;
    }

    public void load(Collection<TermStats> incomingTerms) throws Exception {

        CounterPool counters = new CounterPool();
        for (TermStats stats : incomingTerms) {
            int result = dao.updateGViewerStats(stats);
            String status;
            if (result == 0) {
                status = " MATCHED";
            } else if (result > 0) {
                status = " UPDATED";
            } else {
                status = " INSERTED";
            }

            logger.debug(Thread.currentThread().getName() + "  " + stats.getTermAccId() + status);

            counters.increment(status);
        }

        // dump stats
        int count = counters.get(" MATCHED");
        if( count > 0 ) {
            logger.info("GVIEWER STATS MATCHED: " + Utils.formatThousands(count));
        }
        count = counters.get(" INSERTED");
        if( count > 0 ) {
            logger.info("GVIEWER STATS INSERTED: " + Utils.formatThousands(count));
        }
        count = counters.get(" UPDATED");
        if( count > 0 ) {
            logger.info("GVIEWER STATS UPDATED: " + Utils.formatThousands(count));
        }
    }

    public void setProcessedOntologyPrefixes(Set<String> processedOntologyPrefixes) {
        this.processedOntologyPrefixes = processedOntologyPrefixes;
    }

    public Set getProcessedOntologyPrefixes() {
        return processedOntologyPrefixes;
    }

    // structure used to compute xml data for gviewer
    class XmlInfo {
        int featuresForTerm = 0;
        int featuresWithChilds = 0;

        // to filter out duplicate features
        Set<String> features1 = new HashSet<>();
        Set<String> features2 = new HashSet<>();

        public StringBuilder xmlForTerm = null;
        public StringBuilder xmlWithChilds = null;
    }
}
