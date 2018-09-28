package edu.mcw.rgd.dataload.ontologies;

import edu.mcw.rgd.dao.spring.IntStringMapQuery;
import edu.mcw.rgd.datamodel.MapData;
import edu.mcw.rgd.datamodel.RgdId;
import edu.mcw.rgd.datamodel.SpeciesType;
import edu.mcw.rgd.pipelines.*;
import edu.mcw.rgd.process.Utils;
import edu.mcw.rgd.reporting.Link;
import org.apache.log4j.Logger;

import java.util.*;

/**
 * @author mtutaj
 * Date: Apr 19, 2011
 * <p>
 * populates/updates ONT_TERM_STATS table to be used by ontology report pages and GViewer;
 * if filter is NOT NULL, then GViewer XML data is NOT updated for performance reason
 */
public class GViewerStatsLoader {
    private OntologyDAO dao;
    private Map<String,String> ontPrefixes;

    private final Logger logger = Logger.getLogger("gviewer_stats");
    private String version;

    // maximum number of annotations that will be used;
    // the rest will be ignored
    private int maxAnnotCountPerTerm;

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

        // dump counter statistics
        manager.dumpCounters();

        System.out.println("-- computing gviewer stats -- DONE -- elapsed "+ Utils.formatElapsedTime(time0, System.currentTimeMillis()));

        logger.info("DONE!");
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
                accIdLists.add(dao.getAllTermAccIds(ontPrefix));
                System.out.println("GVIEWER STATS: TERM COUNT for "+ontPrefix+" is "+accIdLists.get(accIdLists.size()-1).size());
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

                        getSession().putRecordToFirstQueue(rec);
                        cnt++;
                    }
                }
            }
            logger.debug("GVIEWER STATS: loaded ontologies, term count="+cnt);
        }
    }

    class QCProcessor extends RecordProcessor {

        int[] primaryMapKey = new int[4];

        public QCProcessor() throws Exception {
            primaryMapKey[SpeciesType.HUMAN] = dao.getPrimaryRefAssemblyMapKey(SpeciesType.HUMAN);
            primaryMapKey[SpeciesType.MOUSE] = dao.getPrimaryRefAssemblyMapKey(SpeciesType.MOUSE);
            primaryMapKey[SpeciesType.RAT] = dao.getPrimaryRefAssemblyMapKey(SpeciesType.RAT);
        }

        public void process(PipelineRecord r) throws Exception {

            PRecord rec = (PRecord) r;
            String accId = rec.stats.getTermAccId();
            rec.stats = dao.getTermWithStats(accId);

            long time0 = System.currentTimeMillis();
            logger.debug(rec.getRecNo() + ". " + Thread.currentThread().getName() + " " + accId + " START");

            getAnnots(rec.stats, SpeciesType.HUMAN);
            getAnnots(rec.stats, SpeciesType.MOUSE);
            getAnnots(rec.stats, SpeciesType.RAT);

            TermStats statsInRgd = dao.getGViewerStats(accId);
            if (!rec.stats.equalsForGViewer(statsInRgd)) {
                rec.setFlag("LOAD");
            }

            long time1 = System.currentTimeMillis();
            logger.debug(rec.getRecNo() + ". " + Thread.currentThread().getName() + "  " + accId + " STOP " + (time1 - time0) + " ms");
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
    }

    class DLProcessor extends RecordProcessor {
        public void process(PipelineRecord r) throws Exception {
            PRecord rec = (PRecord) r;

            int result = dao.updateGViewerStats(rec.stats);
            String status;
            if( result==0 ) {
                status = " MATCHED";
            } else if( result>0 ) {
                status = " UPDATED";
            } else {
                status = " INSERTED";
            }

            logger.debug(Thread.currentThread().getName()+"  "+rec.stats.getTermAccId()+status);
            getSession().incrementCounter("GVIEWER STATS"+status, 1);
        }
    }

    // shared structure to be passed between processing queues
    class PRecord extends PipelineRecord {
        public TermStats stats = new TermStats();
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
