package edu.mcw.rgd.dataload.ontologies;

/*
import edu.mcw.rgd.datamodel.ontologyx.Term;
import edu.mcw.rgd.datamodel.ontologyx.TermSynonym;
import edu.mcw.rgd.process.FileDownloader;
import edu.mcw.rgd.process.Utils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.*;
*/

/**
 * @author mtutaj
 * @since 1/20/14
 * <p>
 * GO taxon constraints ensure that annotations are not made to inappropriate species or sets of species.
 * See http://www.biomedcentral.com/1471-2105/11/530 for more details.
 * </p>
 * http://www.geneontology.org/GO.annotation_qc.shtml#GO_AR:0000013
 * <p>
 * After this module is run, all synonyms from files taxon_union_terms.obo and taxon_go_constraints.obo
 * are refreshed in ONT_SYNONYMS table: synonyms of type 'only_in_taxon' and 'never_in_taxon'.
 * Then these synonyms are enforced and a number of GO terms is tagged with 'Not4Curation' synonyms.
 * (f.e. if a GO term is tagged as 'only_in_taxon Bacteria', this term and all of its child terms are
 *     tagged as 'Not4Curation', because we do not curate bacteria, only Mammals lineage)
 *
 *******
 * Note: this file has been made obsolete in March 2022 because the source files
 * http://www.geneontology.org/quality_control/annotation_checks/taxon_checks/taxon_union_terms.obo and
 * http://www.geneontology.org/quality_control/annotation_checks/taxon_checks/taxon_go_constraints.obo
 * became unavailable (since several months already)
 */
public class TaxonConstraintsOld {

    /*
    private OntologyDAO dao;
    private List<String> ratLineage;
    private Set<Integer> ratLineageSet;

    protected final Logger logger = LogManager.getLogger("goTaxonConstraints");

    // definitions of taxon unions; example entry
    //[Term]
    //id: NCBITaxon_Union:0000007
    //name: Viridiplantae or Bacteria or Euglenozoa
    //namespace: union_terms
    //union_of: NCBITaxon:2 ! Bacteria
    //union_of: NCBITaxon:33090 ! Viridiplantae
    //union_of: NCBITaxon:33682 ! Euglenozoa
    //created_by: Jennifer I Deegan
    //creation_date: 2009-08-10T10:46:43Z
    //
    // in the map we will keep
    //   "NCBITaxon_Union:0000007" ==> {"NCBITaxon:2 ! Bacteria", "NCBITaxon:33090 ! Viridiplantae", "NCBITaxon:33682 ! Euglenozoa"}
    private Map<String, List<String>> taxonUnionMap = new HashMap<>();

    // map of GO terms to list of constraints
    // f.e.
    //  "GO:0007159" ==> { "never_in_taxon NCBITaxon:2 ! Bacteria", "only_in_taxon NCBITaxon:32525"}
    private Map<String, List<String>> taxonConstraintMap = new HashMap<>();

    private String version;
    private String taxonUnionOboFile;
    private String taxonConstraintOboFile;


    public void run() throws Exception {

        System.out.println(getVersion());
        logger.info(getVersion());

        loadTaxonUnionMap();
        loadTaxonConstraints();
        expandTaxonUnions();
        syncSynonyms();

        logger.info("---OK---");
    }

    void loadTaxonUnionMap() throws Exception {

        FileDownloader downloader = new FileDownloader();
        downloader.setExternalFile(taxonUnionOboFile);
        downloader.setLocalFile("data/taxon_union_terms.obo");
        String localFileName = downloader.download();

        BufferedReader reader = new BufferedReader(new FileReader(localFileName));

        String line;
        String taxonUnionId = null;
        List<String> taxonList = null;

        while( (line=reader.readLine())!=null ) {

            if( line.startsWith("id: ") ) {
                taxonUnionId = line.substring(4).trim();
            }
            else if( line.startsWith("union_of: ") ) {
                taxonList.add(line.substring(10).trim());
            }
            else if( line.startsWith("[Term]") ) {
                // flush the previous term
                if( taxonUnionId!=null && taxonUnionId.contains("NCBITaxon_Union") ) {
                    taxonUnionMap.put(taxonUnionId, taxonList);
                }
                // initialize for the next term
                taxonUnionId = null;
                taxonList = new ArrayList<>();
            }
        }
        reader.close();

        // handle the last term
        if( taxonUnionId!=null && taxonUnionId.contains("NCBITaxon_Union") ) {
            taxonUnionMap.put(taxonUnionId, taxonList);
        }

        logger.info("loaded "+taxonUnionMap.size()+ " taxon union entries");
    }

    void loadTaxonConstraints() throws Exception {

        FileDownloader downloader = new FileDownloader();
        downloader.setExternalFile(taxonConstraintOboFile);
        downloader.setLocalFile("data/taxon_go_constraints.obo");
        String localFileName = downloader.download();

        BufferedReader reader = new BufferedReader(new FileReader(localFileName));

        String line;
        String goId = null;
        List<String> taxonList = null;

        while( (line=reader.readLine())!=null ) {

            if( line.startsWith("id: ") ) {
                goId = line.substring(4).trim();
            }
            else if( line.startsWith("relationship: ") ) {
                // sample lines:
                // relationship: only_in_taxon NCBITaxon_Union:0000005 {id="GOTAX:0000102"} ! Nematoda or Protostomia
                // relationship: never_in_taxon NCBITaxon:554915 {id="GOTAX:0000501", source="PMID:21311032"} ! Amoebozoa
                //
                // remove extra text between braces
                String relationship = line.substring(14);
                int startPos = relationship.indexOf("{");
                int stopPos = relationship.indexOf("}");
                if( startPos>0 && stopPos > startPos ) {
                    relationship = relationship.substring(0, startPos) + relationship.substring(stopPos+2);
                }

                // now the relationship should be:
                // only_in_taxon NCBITaxon_Union:0000005 ! Nematoda or Protostomia
                // never_in_taxon NCBITaxon:554915 ! Amoebozoa

                taxonList.add(relationship);
            }
            else if( line.startsWith("[Term]") ) {
                // flush the previous term
                if( goId!=null && goId.startsWith("GO:") ) {
                    taxonConstraintMap.put(goId, taxonList);
                }
                // initialize for the next term
                goId = null;
                taxonList = new ArrayList<>();
            }
        }
        reader.close();

        // handle the last term
        if( goId!=null && goId.startsWith("GO:") ) {
            taxonConstraintMap.put(goId, taxonList);
        }

        logger.info("loaded "+taxonConstraintMap.size()+ " taxon constraints");
    }

    void expandTaxonUnions() {

        int removedTaxonUnionEntries = 0;
        int addedTaxonEntries = 0;

        for( List<String> taxonList: taxonConstraintMap.values() ) {

            List<String> taxonsFromUnions = null;
            Iterator<String> it = taxonList.iterator();
            while( it.hasNext() ) {
                String taxon = it.next();
                if( taxon.contains("NCBITaxon_Union:") ) {
                    // extract relationship (only_in_taxon | never_in_taxon)
                    String relationship = taxon.startsWith("only_in_taxon") ? "only_in_taxon "
                            : taxon.startsWith("never_in_taxon") ? "never_in_taxon "
                            : "? ";

                    // extract id of taxon-union
                    int pos = taxon.indexOf("NCBITaxon_Union:");
                    int pos2 = taxon.indexOf(" ", pos+16); // 16=strlen("NCBITaxon_Union:")
                    String taxonUnionId = taxon.substring(pos, pos2);

                    // expand the union with list of taxons
                    for( String taxonFromUnion: taxonUnionMap.get(taxonUnionId) ) {
                        if( taxonsFromUnions==null )
                            taxonsFromUnions = new ArrayList<>();
                        taxonsFromUnions.add(relationship + taxonFromUnion);
                    }

                    // remove union from the GO results
                    it.remove();
                    removedTaxonUnionEntries++;
                }
            }

            // add expanded taxons from unions, if any
            if( taxonsFromUnions!=null ) {
                taxonList.addAll(taxonsFromUnions);
                addedTaxonEntries += taxonsFromUnions.size();
            }
        }

        logger.info("expanded "+removedTaxonUnionEntries+ " taxon union entries into "+addedTaxonEntries+" taxon entries");
    }

    void syncSynonyms() throws Exception {

        List<TermSynonym> synonymsForInsert = new ArrayList<>();
        List<TermSynonym> synonymsForDelete = new ArrayList<>();
        List<TermSynonym> synonymsMatching = new ArrayList<>();

        for( Map.Entry<String, List<String>> entry: this.taxonConstraintMap.entrySet() ) {

            String goId = entry.getKey();
            List<String> taxons = entry.getValue();
            List<TermSynonym> incomingSynonyms = new ArrayList<>();

            // create list of incoming synonyms
            for( String taxon: taxons ) {
                int spacePos = taxon.indexOf(' ');

                // handle only_in_taxon, never_in_taxon synonyms
                TermSynonym syn = new TermSynonym();
                syn.setTermAcc(goId);
                syn.setType(taxon.substring(0, spacePos).trim());
                syn.setName(taxon.substring(spacePos + 1).trim());
                addSynonym(incomingSynonyms, syn);

                // handle Not4Curation synonyms
                if( satisfiesTaxonConstraints(goId, taxon) ) {
                    //System.out.println("OK: satisfies taxon constraint "+goId+" "+ taxon);
                } else {
                    //System.out.println("BAD: not satisfies taxon constraint "+goId+" "+ taxon);

                    syn = new TermSynonym();
                    syn.setTermAcc(goId);
                    syn.setType("synonym");
                    syn.setName("Not4Curation");
                    addSynonym(incomingSynonyms, syn);

                    for( Term term: dao.getAllActiveTermDescendants(goId) ) {
                        syn = new TermSynonym();
                        syn.setTermAcc(term.getAccId());
                        syn.setType("synonym");
                        syn.setName("Not4Curation");
                        addSynonym(incomingSynonyms, syn);
                    }
                }
            }

            // create list of in-RGD synonyms
            List<TermSynonym> inRgdSynonyms = dao.getTermSynonyms(goId);
            Iterator<TermSynonym> it = inRgdSynonyms.iterator();
            while( it.hasNext() ) {
                TermSynonym syn = it.next();
                if( !syn.getType().equals("only_in_taxon") &&
                    !syn.getType().equals("never_in_taxon") &&
                    !syn.getName().equals("Not4Curation") ) {
                    it.remove();
                }
            }

            // do qc
            SynonymManager synonymManager = new SynonymManager();
            synonymManager.qc(goId, inRgdSynonyms, incomingSynonyms);

            // determine for-insert and for-delete synonyms
            synonymsForInsert.addAll(synonymManager.forInsertSynonyms);
            synonymsForDelete.addAll(synonymManager.forDeleteSynonyms);
            for( TermSynonym syn: synonymManager.matchingSynonyms ) {
                addSynonym(synonymsMatching, syn);
            }
        }

        // perform synonym operations on database
        for( TermSynonym syn: synonymsForInsert ) {
            dao.insertTermSynonym(syn, "GO");
        }
        dao.deleteTermSynonyms(synonymsForDelete);

        printStats("inserted", synonymsForInsert);
        printStats("deleted", synonymsForDelete);
        printStats("matching", synonymsMatching);
    }

    void printStats(String title, List<TermSynonym> synonyms) {
        logger.info("LOAD TAXON ENTRIES: "+title+" term synonyms "+synonyms.size());

        int countNeverInTaxon = 0;
        int countOnlyInTaxon = 0;
        int countNot4Curation = 0;
        for( TermSynonym syn: synonyms ) {
            if( syn.getType().equals("never_in_taxon") ) {
                countNeverInTaxon++;
            }
            else if( syn.getType().equals("only_in_taxon") ) {
                countOnlyInTaxon++;
            }
            else if( syn.getName().equals("Not4Curation") ) {
                countNot4Curation++;
            }
            else {
                System.out.println("ERROR: unpected synonym type/name");
            }
        }
        String msg;
        if( countNeverInTaxon>0 ) {
            msg = "  never_in_taxon synonyms "+title+": "+countNeverInTaxon;
            logger.info(msg);
            System.out.println(msg);
        }
        if( countOnlyInTaxon>0 ) {
            msg = "  only_in_taxon synonyms "+title+": "+countOnlyInTaxon;
            logger.info(msg);
            System.out.println(msg);
        }
        if( countNot4Curation>0 ) {
            msg = "  Not4Curation synonyms "+title+": "+countNot4Curation;
            logger.info(msg);
            System.out.println(msg);
        }
    }

    // do not add duplicate synonyms
    void addSynonym(List<TermSynonym> synonyms, TermSynonym syn) {

        for( TermSynonym ts: synonyms ) {
            if(Utils.stringsAreEqual(ts.getTermAcc(), syn.getTermAcc()) &&
                Utils.stringsAreEqual(ts.getType(), syn.getType()) &&
                Utils.stringsAreEqual(ts.getName(), syn.getName()) ) {
                return;
            }
        }
        synonyms.add(syn);
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getVersion() {
        return version;
    }

    public void setTaxonUnionOboFile(String taxonUnionOboFile) {
        this.taxonUnionOboFile = taxonUnionOboFile;
    }

    public String getTaxonUnionOboFile() {
        return taxonUnionOboFile;
    }

    public void setTaxonConstraintOboFile(String taxonConstraintOboFile) {
        this.taxonConstraintOboFile = taxonConstraintOboFile;
    }

    public String getTaxonConstraintOboFile() {
        return taxonConstraintOboFile;
    }

    public OntologyDAO getDao() {
        return dao;
    }

    public void setDao(OntologyDAO dao) {
        this.dao = dao;
    }

    public void setRatLineage(List<String> ratLineage) {
        this.ratLineage = ratLineage;
    }

    public List<String> getRatLineage() {
        return ratLineage;
    }

    public void init() {

        if( ratLineageSet!=null )
            return;

        // create a set with taxon ids for rat lineage
        ratLineageSet = new HashSet<>();
        for( String taxon: ratLineage ) {
            int pos = taxon.indexOf(' ');
            int taxId = Integer.parseInt(taxon.substring(0, pos));
            ratLineageSet.add(taxId);
        }
    }

    public boolean satisfiesNeverInTaxonConstraint(int taxonId) {
        // the specific taxon id MUST NOT be in rat lineage
        return !ratLineageSet.contains(taxonId);
    }

    public boolean satisfiesOnlyInTaxonConstraint(int taxonId) {
        // the specific taxon id MUST be in rat lineage
        return ratLineageSet.contains(taxonId);
    }


    public boolean satisfiesTaxonConstraints(String termAcc, String taxon) throws Exception {
        // taxon constraints are only for GO terms
        if( !termAcc.startsWith("GO:") )
            return true;

        // lazy init
        init();

        // extract taxon id
        int pos1 = taxon.indexOf("NCBITaxon:");
        if( pos1<0 )
            return true;
        pos1 += 10; // go to right after 'NCBITaxon:'
        int pos2 = taxon.indexOf(' ', pos1);
        int taxonId = Integer.parseInt(taxon.substring(pos1, pos2));

        pos1 = taxon.indexOf(' ');
        if( pos1>0 ) {
            String taxonConstraintType = taxon.substring(0, pos1);
            switch( taxonConstraintType ) {
                case "only_in_taxon":
                    return satisfiesOnlyInTaxonConstraint(taxonId);
                case "never_in_taxon":
                    return satisfiesNeverInTaxonConstraint(taxonId);
                default:
                    throw new Exception("Unknown taxon constraint type: "+taxonConstraintType);
            }
        }
        return true;
    }
    */
}
