package edu.mcw.rgd.dataload.ontologies;

import edu.mcw.rgd.dataload.ontologies.test.EfoXrefCreator;
import edu.mcw.rgd.dataload.ontologies.test.SSSOMValidator;
import edu.mcw.rgd.datamodel.ontologyx.Relation;
import edu.mcw.rgd.datamodel.ontologyx.TermDagEdge;
import edu.mcw.rgd.datamodel.ontologyx.TermSynonym;
import edu.mcw.rgd.process.CounterPool;
import edu.mcw.rgd.process.Utils;
import org.apache.logging.log4j.LogManager;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
import org.springframework.core.io.FileSystemResource;

import java.io.*;
import java.util.*;

/**
 * @author mtutaj
 * Date: 12/28/10
 */
public class Manager {

    private Map<String,String> oboFiles;
    private OntologyDAO dao;
    private String version;
    private QualityChecker qualityChecker;
    private DataLoader dataLoader;
    private String malformedRsSynonymsEmailList;
    private String apiKeyFile;

    static private Set<String> ONTOLOGIES_EXCLUDED_FROM_PROCESSING = Set.of("CMO", "MMO", "XCO", "RS", "PW", "RDO");


    public static void main(String[] args) throws Exception {
        try {
            main2(args);

        } catch(Exception e) {
            Utils.printStackTrace(e, LogManager.getLogger("status"));
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

        String singleOntologyId = null;
        int qcThreadCount = 5;
        boolean goTaxonConstraints = false;
        String filter = null;
        boolean checkForCycles = false;
        boolean omimPsCustomDoMapper = false;
        boolean sssomGenerator = false;
        String sssomFile = null;
        boolean generateEfoXrefs = false;

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
            else if (arg.startsWith("-filter=") ) {
                filter = arg.substring(8);
                if (filter.equals("")) {
                    filter=null;
                }
            } else if (arg.startsWith("-checkForCycles") || arg.startsWith("-check_for_cycles")) {
                checkForCycles = true;
            } else if (arg.startsWith("-omim_ps_custom_do_mapper")) {
                omimPsCustomDoMapper = true;
            } else if (arg.startsWith("-sssom_generator")) {
                sssomGenerator = true;
            } else if (arg.startsWith("-sssom_file=")) {
                sssomFile = arg.substring(12);
            } else if (arg.startsWith("-efo_xrefs")) {
                generateEfoXrefs = true;
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

        //download external files and setup file parser
        if( !skipDownloads ) {
            parser = (FileParser) bf.getBean("fileParser");
            parser.setDao(manager.dao);
            parser.enforceSingleOntology(singleOntologyId);

            manager.downloadAndProcessExternalFiles(parser);
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
            loader1.run(parser.getOntPrefixes());

        }

        // generate obo files
        if( generateOboFile!=null ) {
            OboFileCreator oboFileCreator = (OboFileCreator) bf.getBean("oboFileGenerator");
            oboFileCreator.run(generateOboFile);
        }

        // generate obo files
        if( goTaxonConstraints ) {
            TaxonConstraints taxonConstraints = (TaxonConstraints) bf.getBean("goTaxonConstraints");
            taxonConstraints.setDao(manager.dao);
            taxonConstraints.run();
        }

        if( omimPsCustomDoMapper ) {
            OmimPsCustomDoMapper psDoMapper = (OmimPsCustomDoMapper) bf.getBean("omimPsCustomDoMapper");
            psDoMapper.run();
        }

        if( sssomGenerator ) {
            SSSOMGenerator _sssomGenerator = (SSSOMGenerator) bf.getBean("sssomGenerator");
            _sssomGenerator.run();
        }

        if( sssomFile!=null ) {
            SSSOMValidator sssomValidator = (SSSOMValidator) bf.getBean("sssomValidator");
            sssomValidator.run(sssomFile);
        }

        if( generateEfoXrefs ) {
            EfoXrefCreator efoXrefCreator = (EfoXrefCreator) bf.getBean("efoXrefGenerator");
            efoXrefCreator.run();
        }
    }

    /**
     * prints usage information about program arguments
     */
    public static void usage() {

        System.out.println("""
            Usage: OntologyLoad pipeline
               You can use any combination of following arguments:
               -skip_downloads       no downloads and no file parsing is performed
                                     and no new data will be loaded/updated in database
               -skip_stats_update    ontology statistics is not recomputed
                                     this is not recommended when data has been updated or loaded
               -single_ontology=?    run the load only for single ontology as specified after '='
                                     f.e. '-single_ontology=PW'
               -generate_obo_file=?  generate .obo file from database for given ontology
                                     f.e. '-generate_obo_file=RDO'
                                          '-generate_obo_file='  generates .obo files for all ontologies as specified in AppConfigure.xml
               -go_taxon_constraints load taxon constraints for GO terms into RGD
                                     f.e. '-go_taxon_constraints'
               -qc_thread_count=?    specify count of qc threads; default is 5
                                     f.e. '-qc_thread_count=2'
               -update_ps_do_custom_mappings update OMIM_PS_DO_CUSTOM table
               -?                    print usage and exit
               -help                 print usage and exit
               --help                print usage and exit
            """);
    }

    /**
     * modifies configuration data so only data pertaining to given ontology will stay
     *
     * @param ontId ontology id to be processed
     * @return ontId
     */
    String enforceSingleOntology(String ontId) {
        // modify oboFiles map: only the selected ontology should stay
        oboFiles.keySet().removeIf(curOntId -> !curOntId.equalsIgnoreCase(ontId));
        return ontId;
    }

    /**
     * download external files and process them
     * @param parser FileParser object
     * @throws Exception
     */
    void downloadAndProcessExternalFiles(FileParser parser) throws Exception {

        long time0 = System.currentTimeMillis();

        parser.setApiKey( Utils.readFileAsString(getApiKeyFile()).trim() );
        qualityChecker.setDao(dao);
        dataLoader.setDao(dao);

        // first thread group: break obo files into a stream of records
        for( Map.Entry<String,String> entry: getOboFiles().entrySet() ) {
            String ontId = entry.getKey();
            String path = entry.getValue();

            if( ONTOLOGIES_EXCLUDED_FROM_PROCESSING.contains(ontId) ) {
                System.out.println("*** ontology excluded from processing: "+ontId);
                continue;
            }

            CounterPool counters = new CounterPool();

            List<Record> records = parser.process(ontId, path, counters);

            records.parallelStream().forEach( rec -> {

                try {
                    qualityChecker.process(rec, counters);
                    dataLoader.process(rec, counters);
                } catch( Exception e ) {
                    throw new RuntimeException(e);
                }
            });


            parser.postProcess();

            dropStaleSynonyms(time0, counters);

            handleMalformedRsSynonyms(time0);

            obsoleteOrphanedTerms(counters, parser.getOntPrefixes().keySet());

            deleteCyclicRelations(counters, parser.getOntPrefixes().keySet());

            // dump counter statistics to STDOUT
            System.out.println(counters.dumpAlphabetically());
        }

        System.out.println("--SUCCESS -- "+ Utils.formatElapsedTime(time0, System.currentTimeMillis()));
    }

    void deleteCyclicRelations(CounterPool counters, Set<String> ontPrefixes) throws Exception {
        // TODO: this hard-coded exceptions should be put in property file
        int cyclesDeleted = 0;
        for (String ontPrefix: ontPrefixes) {
            // cyclic relations in UBERON, that started appearing in July 2021
            if( ontPrefix.equals("UBERON") ) {
                try {
                    // verify if this is still a problem
                    dao.getAncestorCount("UBERON:8000009");
                } catch(Exception e) {
                    TermDagEdge dag = new TermDagEdge();
                    dag.setParentTermAcc("UBERON:8000009");
                    dag.setChildTermAcc("UBERON:0002354");
                    dag.setRel(Relation.PART_OF);
                    dao.deleteDag(dag);
                    cyclesDeleted++;
                }
            }
            // cyclic relations in GO CC, that started appearing in June 2024
            else if( ontPrefix.equals("GO") ) {
                try {
                    // verify if this is still a problem
                    dao.getAncestorCount("GO:0045258");
                } catch(Exception e) {
                    TermDagEdge dag = new TermDagEdge();
                    dag.setRel(Relation.PART_OF);

                    dag.setParentTermAcc("GO:0045258");
                    dag.setChildTermAcc( "GO:0045282");
                    dao.deleteDag(dag);
                    cyclesDeleted++;

                    dag.setParentTermAcc("GO:0045257");
                    dag.setChildTermAcc( "GO:0045281");
                    dao.deleteDag(dag);
                    cyclesDeleted++;

                    dag.setParentTermAcc("GO:0045274");
                    dag.setChildTermAcc( "GO:0045282");
                    dao.deleteDag(dag);
                    cyclesDeleted++;

                    dag.setParentTermAcc("GO:0045273");
                    dag.setChildTermAcc( "GO:0045281");
                    dao.deleteDag(dag);
                    cyclesDeleted++;
                }
            }
            else if( ontPrefix.equals("EFO") ) {
                try {
                    // verify if this is still a problem
                    dao.getAncestorCount("EFO:CHEBI:36080");
                } catch(Exception e) {
                    TermDagEdge dag = new TermDagEdge();
                    dag.setParentTermAcc("EFO:CHEBI:36080");
                    dag.setChildTermAcc("EFO:PR:000064867");
                    dag.setRel(Relation.HAS_PART);
                    dao.deleteDag(dag);
                    cyclesDeleted++;
                }
            }
        }
        if( cyclesDeleted!=0 ) {
            counters.add("CYCLIC_RELATIONS_DROPPED_FROM_DAG", cyclesDeleted);
        }
    }

    void obsoleteOrphanedTerms(CounterPool counters, Set<String> ontPrefixes) throws Exception {
        // terms that once were part of ontology dag tree, but are no longer
        int obsoleteTermCount = 0;
        for (String ontPrefix: ontPrefixes) {
            if( MalformedOboFiles.getInstance().isWellFormed(ontPrefix) ) {
                int obsoleteCount = dao.obsoleteOrphanedTerms(ontPrefix);
                if( obsoleteCount!=0 ) {
                    counters.add("ORPHANED_TERMS_MADE_OBSOLETE_"+ontPrefix, obsoleteCount);
                    obsoleteTermCount += obsoleteCount;
                }
            }
        }
        if( obsoleteTermCount!=0 ) {
            counters.add("ORPHANED_TERMS_MADE_OBSOLETE", obsoleteTermCount);
        }
    }

    void dropStaleSynonyms(long time0, CounterPool counters) throws Exception {

        // drop any stale synonyms
        for( String ontId: getOboFiles().keySet() ) {
            // skip dropping of stale synonyms if the obo file is malformed
            if( !MalformedOboFiles.getInstance().isWellFormed(ontId) )
                continue;

            if( ontId.equals("GO") ) {
                dropStaleSynonyms("CC", time0, counters);
                dropStaleSynonyms("MF", time0, counters);
                dropStaleSynonyms("BP", time0, counters);
            }
            else {
                dropStaleSynonyms(ontId, time0, counters);
            }
        }
    }

    void dropStaleSynonyms(String ontId, long time0, CounterPool counters) throws Exception {

        // drop any stale synonyms
        List<TermSynonym> staleSynonyms = dao.getTermSynonymsModifiedBefore(ontId, "OBO", new Date(time0));
        int deleted = dao.deleteTermSynonyms(staleSynonyms);
        int skipped = staleSynonyms.size()-deleted;
        if( deleted!=0 ) {
            counters.add("SYNONYMS_STALE_DROPPED_FOR_"+ontId, deleted);
        }
        if( skipped!=0 ) {
            counters.add("SYNONYMS_STALE_SKIPPED_FOR_" + ontId, skipped);
        }
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

    public OntologyDAO getDao() {
        return dao;
    }

    public void setDao(OntologyDAO dao) {
        this.dao = dao;
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

    public String getApiKeyFile() {
        return apiKeyFile;
    }

    public void setApiKeyFile(String apiKeyFile) {
        this.apiKeyFile = apiKeyFile;
    }
}
