package edu.mcw.rgd.dataload.ontologies;

import edu.mcw.rgd.datamodel.ontologyx.TermSynonym;
import edu.mcw.rgd.pipelines.PipelineManager;
import edu.mcw.rgd.pipelines.PipelineSession;
import edu.mcw.rgd.process.Utils;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
import org.springframework.core.io.FileSystemResource;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * @author mtutaj
 * Date: 12/28/10
 */
public class Manager {

    private Map<String,String> oboFiles;
    private OntologyDAO dao = new OntologyDAO();
    private String version;
    private QualityChecker qualityChecker;
    private DataLoader dataLoader;
    private String malformedRsSynonymsEmailList;

    public static void main(String[] args) throws Exception {
        try {
            main2(args);
        } catch(Exception e) {
            e.printStackTrace();
            throw e;
        }
    }

    public static void main2(String[] args) throws Exception {

        DefaultListableBeanFactory bf = new DefaultListableBeanFactory();
        new XmlBeanDefinitionReader(bf).loadBeanDefinitions(new FileSystemResource("properties/AppConfigure.xml"));
        //new XmlBeanDefinitionReader(bf).loadBeanDefinitions(new FileSystemResource("C:\\cygwin\\home\\jdepons\\rgd\\dev\\pipelines\\OntologyLoad\\trunk\\properties\\AppConfigure.xml"));
        Manager manager = (Manager) (bf.getBean("manager"));

        System.out.println(manager.getVersion());

        boolean skipDownloads = false;
        boolean skipStatsUpdate = false;

        String generateOboFile = null; // if not null, contains ontology id for which .obo file should be generated
        boolean processObsoleteTerms = false;
        boolean prodRelease = false; // generate versioned obo files for prod release

        boolean dropSynonyms = false;
        String singleOntologyId = null;
        int qcThreadCount = 5;
        boolean goTaxonConstraints = false;
        String filter = null;
        boolean loadGViewerStats = false;
        boolean checkForCycles = false;

        for( String arg: args ) {
            if( arg.startsWith("-skip_download") ) {
                skipDownloads = true;
            }
            else if( arg.equals("-skip_stats_update") ) {
                skipStatsUpdate = true;
            }
            else if( arg.startsWith("-generate_obo_file=") ) {
                generateOboFile = arg.substring(19);
            }
            else if( arg.equals("-process_obsolete_terms") ) {
                processObsoleteTerms = true;
            }
            else if( arg.equals("-prod_release") ) {
                prodRelease = true;
            }
            else if( arg.equals("-drop_synonyms") ) {
                dropSynonyms = true;
            }
            else if( arg.startsWith("-single_ontology=") ) {
                // single ontology id follows the arg
                singleOntologyId = manager.enforceSingleOntology(arg.substring(17));
            }
            else if( arg.startsWith("-qc_thread_count=") ) {
                int newQcThreadCount = Integer.parseInt(arg.substring(17));
                if( newQcThreadCount>0 && newQcThreadCount<=25 )
                    qcThreadCount = newQcThreadCount;
            }
            else if( arg.equals("-go_taxon_constraints") ) {
                goTaxonConstraints = true;
            }
            else if( arg.equals("-gviewer_stats") ) {
                loadGViewerStats = true;
            } else if (arg.startsWith("-filter=") ) {
                filter = arg.substring(8);
                if (filter.equals("")) {
                    filter=null;
                }
            } else if (arg.startsWith("-checkForCycles") || arg.startsWith("-check_for_cycles")) {
                checkForCycles = true;
            }

            if( arg.equals("-?") || arg.equals("-help") || arg.equals("--help") ) {
                usage();
                return;
            }
        }

        if( singleOntologyId==null ) {
            skipDownloads = true;
            skipStatsUpdate = true;
        } else {
            System.out.println("running " + singleOntologyId + " with filter " + filter);
        }

        if( checkForCycles ) {
            manager.dao.checkForCycles(singleOntologyId);
        }

        FileParser parser = null;

        // update gviewer xml stats
        if( loadGViewerStats ) {
            if( parser==null ) {
                parser = (FileParser) bf.getBean("fileParser");
                parser.setDao(manager.dao);
                parser.enforceSingleOntology(singleOntologyId);
            }
            GViewerStatsLoader loader2 = (GViewerStatsLoader) bf.getBean("GViewerStatsLoader");
            loader2.setDao(manager.dao);
            loader2.run(parser.getOntPrefixes(), qcThreadCount);
            return;
        }

        //download external files and setup file parser
        if( !skipDownloads ) {
            if( parser==null ) {
                parser = (FileParser) bf.getBean("fileParser");
                parser.setDao(manager.dao);
                parser.enforceSingleOntology(singleOntologyId);
            }
            manager.downloadAndProcessExternalFiles(parser, dropSynonyms, qcThreadCount);
        }

        // update term stats
        if( !skipStatsUpdate ) {
            if( parser==null ) {
                parser = (FileParser) bf.getBean("fileParser");
                parser.setDao(manager.dao);
                parser.enforceSingleOntology(singleOntologyId);
            }
            TermStatsLoader loader1 = (TermStatsLoader) bf.getBean("termStatLoader");
            loader1.setDao(manager.dao);
            loader1.setFilter(filter);
            loader1.run(parser.getOntPrefixes(), qcThreadCount);

        }

        // generate obo files
        if( generateOboFile!=null ) {
            OboFileCreator oboFileCreator = (OboFileCreator) bf.getBean("oboFileGenerator");
            oboFileCreator.run(generateOboFile, processObsoleteTerms, prodRelease);
        }

        // generate obo files
        if( goTaxonConstraints ) {
            TaxonConstraints taxonConstraints = (TaxonConstraints) bf.getBean("goTaxonConstraints");
            taxonConstraints.setDao(manager.dao);
            taxonConstraints.run();
        }
    }

    /**
     * prints usage information about program arguments
     */
    public static void usage() {

        System.out.println(
            "Usage: OntologyLoad pipeline\n"+
            "   You can use any combination of following arguments:\n"+
            "   -skip_downloads       no downloads and no file parsing is performed\n"+
            "                         and no new data will be loaded/updated in database\n"+
            "   -skip_stats_update    ontology statistics is not recomputed\n"+
            "                         this is not recommended when data has been updated or loaded\n"+
            "   -single_ontology=?    run the load only for single ontology as specified after '='\n"+
            "                         f.e. '-single_ontology=PW'\n"+
            "   -generate_obo_file=?  generate .obo file from database for given ontology\n"+
            "                         f.e. '-generate_obo_file=RDO'\n"+
            "                              '-generate_obo_file=MMO -process_obsolete_terms -prod_release'\n"+
            "   -drop_synonyms        drop synonyms before loading synonyms read from obo file;\n"+
            "                         if this option is not specified on the command line, the existing synonyms\n"+
            "                         will be retained and only new ones imported into database\n"+
            "   -go_taxon_constraints load taxon constraints for GO terms into RGD\n"+
            "                         f.e. '-go_taxon_constraints'\n"+
            "   -qc_thread_count=?    specify count of qc threads; default is 5\n"+
            "                         f.e. '-qc_thread_count=2'\n"+
            "   -?                    print usage and exit\n"+
            "   -help                 print usage and exit\n"+
            "   --help                print usage and exit\n"+
            "");
    }

    /**
     * modifies configuration data so only data pertaining to given ontology will stay
     *
     * @param ontId ontology id to be processed
     * @return ontId
     */
    String enforceSingleOntology(String ontId) {
        // modify oboFiles map: only the selected ontology should stay
        Iterator<String> it = oboFiles.keySet().iterator();
        while( it.hasNext() ) {
            String curOntId = it.next();
            if( !curOntId.equalsIgnoreCase(ontId) ) {
                it.remove();
            }
        }
        return ontId;
    }

    /**
     * download external files and process them
     * @param parser FileParser object
     * @param dropSynonyms if true, existing synonyms will be dropped from
     * @throws Exception
     */
    void downloadAndProcessExternalFiles(FileParser parser, boolean dropSynonyms, int qcThreadCount) throws Exception {

        long time0 = System.currentTimeMillis();

        parser.setOboFiles(getOboFiles());

        // create pipeline manager
        PipelineManager manager = new PipelineManager();
        // first thread group: break obo files into a stream of records
        manager.addPipelineWorkgroup(parser, "FP", getOboFiles().size(), 0);

        // another thread group: perform quality checking in 5 thread
        qualityChecker.setDao(dao);
        manager.addPipelineWorkgroup(qualityChecker, "QC", qcThreadCount, 0);

        // last thread group: perform data loading in 1 thread
        dataLoader.setDao(dao);
        manager.addPipelineWorkgroup(dataLoader, "DL", 1, 0);

        // read the thread counts in each group
        PipelineSession session = manager.getSession();

        // drop synonyms for processed ontologies
        if( dropSynonyms ) {
            dropSynonyms(session);
        }

        // run everything
        manager.run();

        parser.postProcess();

        dropStaleSynonyms(time0, session);

        handleMalformedRsSynonyms(time0);

        obsoleteOrphanedTerms(manager.getSession(), parser.getOntPrefixes().keySet());

        // dump counter statistics to STDOUT
        manager.dumpCounters();

        System.out.println("--SUCCESS -- "+ Utils.formatElapsedTime(time0, System.currentTimeMillis()));
    }

    void obsoleteOrphanedTerms(PipelineSession session, Set<String> ontPrefixes) throws Exception {
        // terms that once were part of ontology dag tree, but are no longer
        int obsoleteTermCount = 0;
        for (String ontPrefix: ontPrefixes) {
            if( MalformedOboFiles.getInstance().isWellFormed(ontPrefix) ) {
                int obsoleteCount = dao.obsoleteOrphanedTerms(ontPrefix);
                session.incrementCounter("ORPHANED_TERMS_MADE_OBSOLETE_"+ontPrefix, obsoleteCount);
                obsoleteTermCount += obsoleteCount;
            }
        }
        session.incrementCounter("ORPHANED_TERMS_MADE_OBSOLETE", obsoleteTermCount);
    }

    void dropSynonyms(PipelineSession session) throws Exception {

        for( String ontId: getOboFiles().keySet() ) {
            int count = dao.dropSynonyms(ontId);
            session.incrementCounter("SYNONYMS_DROPPED_FOR_"+ontId, count);
        }
    }

    void dropStaleSynonyms(long time0, PipelineSession session) throws Exception {

        // drop any stale synonyms
        for( String ontId: getOboFiles().keySet() ) {
            // skip dropping of stale synonyms if the obo file is malformed
            if( !MalformedOboFiles.getInstance().isWellFormed(ontId) )
                continue;

            if( ontId.equals("GO") ) {
                dropStaleSynonyms("CC", time0, session);
                dropStaleSynonyms("MF", time0, session);
                dropStaleSynonyms("BP", time0, session);
            }
            else {
                dropStaleSynonyms(ontId, time0, session);
            }
        }
    }

    void dropStaleSynonyms(String ontId, long time0, PipelineSession session) throws Exception {

        // drop any stale synonyms
        List<TermSynonym> staleSynonyms = dao.getTermSynonymsModifiedBefore(ontId, "OBO", new Date(time0));
        int deleted = dao.deleteTermSynonyms(staleSynonyms);
        int skipped = staleSynonyms.size()-deleted;
        session.incrementCounter("SYNONYMS_STALE_DROPPED_FOR_"+ontId, deleted);
        session.incrementCounter("SYNONYMS_STALE_SKIPPED_FOR_"+ontId, skipped);
    }

    void handleMalformedRsSynonyms(long time0) throws IOException {

        // see if there is a file with malformed synonyms
        File file = new File("logs/malformedRsSynonyms.log");
        if( file.exists() && file.lastModified()>time0 ) {
            String [] recipients = getMalformedRsSynonymsEmailList().split("[,]");
            String subject = "malformed RGD ID synonyms for RS terms";
            String message = Utils.readFileAsString(file.getAbsolutePath());
            Utils.sendMail(recipients, subject, message);
        }
    }

    public Map<String, String> getOboFiles() {
        return oboFiles;
    }

    public void setOboFiles(Map<String, String> oboFiles) {
        this.oboFiles = oboFiles;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getVersion() {
        return version;
    }

    public void setQualityChecker(QualityChecker qualityChecker) {
        this.qualityChecker = qualityChecker;
    }

    public QualityChecker getQualityChecker() {
        return qualityChecker;
    }

    public void setDataLoader(DataLoader dataLoader) {
        this.dataLoader = dataLoader;
    }

    public DataLoader getDataLoader() {
        return dataLoader;
    }

    public void setMalformedRsSynonymsEmailList(String malformedRsSynonymsEmailList) {
        this.malformedRsSynonymsEmailList = malformedRsSynonymsEmailList;
    }

    public String getMalformedRsSynonymsEmailList() {
        return malformedRsSynonymsEmailList;
    }
}
