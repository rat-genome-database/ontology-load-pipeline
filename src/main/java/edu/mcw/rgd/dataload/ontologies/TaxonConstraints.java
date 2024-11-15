package edu.mcw.rgd.dataload.ontologies;

import edu.mcw.rgd.datamodel.ontologyx.Term;
import edu.mcw.rgd.datamodel.ontologyx.TermSynonym;
import edu.mcw.rgd.process.FileDownloader;
import edu.mcw.rgd.process.Utils;
import org.apache.commons.collections4.ListUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
import java.util.*;

/**
 * @author mtutaj
 * @since 3/24/22
 * <p>
 * GO taxon constraints ensure that annotations are not made to inappropriate species or sets of species.
 * <p>
 * Synonyms of type 'only_in_taxon' and 'never_in_taxon' are loaded and then enforced,
 * and a number of GO terms is tagged with 'Not4Curation' synonyms.
 * (f.e. if a GO term is tagged as 'only_in_taxon Bacteria', this term and all of its child terms are
 *     tagged as 'Not4Curation', because we do not curate bacteria, only Mammals lineage)
 *
 */
public class TaxonConstraints {

    private OntologyDAO dao;
    private List<String> ratLineage;
    private Set<Integer> ratLineageSet;

    protected final Logger logger = LogManager.getLogger("goTaxonConstraints");
    protected final Logger logStatus = LogManager.getLogger("status");

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
    private String onlyInTaxonFile;
    private String neverInTaxonFile;


    public void run() throws Exception {

        logger.info(getVersion());

        loadTaxonUnionMap();
        loadTaxonConstraints();
        expandTaxonUnions();
        syncSynonyms();

        logger.info("---OK---");
        logger.info("");
    }

    void loadTaxonUnionMap() throws Exception {

        FileDownloader downloader = new FileDownloader();
        downloader.setExternalFile(taxonUnionOboFile);
        downloader.setLocalFile("data/taxon_union_terms.obo");
        downloader.setUseCompression(true);
        downloader.setPrependDateStamp(true);
        String localFileName = downloader.download();

        BufferedReader reader = Utils.openReader(localFileName);

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

        int neverInTaxonConstraints = loadConstraints("never_in_taxon", getNeverInTaxonFile());
        int onlyInTaxonConstraints = loadConstraints("only_in_taxon", getOnlyInTaxonFile());

        logger.info("loaded "+taxonConstraintMap.size()+ " taxon constraints");
        logger.info("    never_in_taxon constraints: "+neverInTaxonConstraints);
        logger.info("    only_in_taxon constraints : "+onlyInTaxonConstraints);
    }

    int loadConstraints(String prefix, String fileName) throws Exception {

        int loadedConstraints = 0;

        FileDownloader downloader = new FileDownloader();
        downloader.setExternalFile(fileName);
        downloader.setLocalFile("data/"+prefix+".tsv");
        downloader.setUseCompression(true);
        downloader.setPrependDateStamp(true);
        String localFileName = downloader.downloadNew();

        BufferedReader reader = Utils.openReader(localFileName);

        String line;

        while( (line=reader.readLine())!=null ) {

            String[] cols = line.split("[\\t]", -1);
            if( cols.length<4 ) {
                continue;
            }
            String goId = cols[0].trim();
            String taxon = cols[2];
            String taxonLabel = cols[3];
            if( !(goId.startsWith("GO:") && taxon.startsWith("NCBITaxon")) ) {
                continue;
            }

            // format of taxon union ids in obo file and tsv file differs; we must unify them
            if( taxon.startsWith("NCBITaxon:Union_") ) {
                taxon = taxon.replace("NCBITaxon:Union_", "NCBITaxon_Union:");
            }

            List<String> taxonList = taxonConstraintMap.get(goId);
            if( taxonList==null ) {
                taxonList = new ArrayList<>();
                taxonConstraintMap.put(goId, taxonList);
            }

            String taxonLine = prefix+" "+taxon+" ! "+taxonLabel;
            taxonList.add(taxonLine);
            loadedConstraints++;
        }
        reader.close();

        return loadedConstraints;
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
                        taxonsFromUnions.add(relationship + taxonFromUnion + taxon.substring(pos2));
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

        List<TermSynonym> incomingSynonyms = new ArrayList<>();

        for( Map.Entry<String, List<String>> entry: this.taxonConstraintMap.entrySet() ) {

            String goId = entry.getKey();
            List<String> taxons = entry.getValue();

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
                    logStatus.debug("OK: satisfies taxon constraint "+goId+" "+ taxon);
                } else {
                    logStatus.debug("BAD: not satisfies taxon constraint "+goId+" "+ taxon);

                    syn = new TermSynonym();
                    syn.setTermAcc(goId);
                    syn.setType("synonym");
                    syn.setName("Not4Curation");
                    addSynonym(incomingSynonyms, syn);

                    List<Term> childTerms = dao.getAllActiveTermDescendants(goId);
                    for( Term term: childTerms ) {
                        syn = new TermSynonym();
                        syn.setTermAcc(term.getAccId());
                        syn.setType("synonym");
                        syn.setName("Not4Curation");
                        addSynonym(incomingSynonyms, syn);
                    }
                }
            }
        }

        syncSynonymsWithRgd(incomingSynonyms);
    }

    void syncSynonymsWithRgd(List<TermSynonym> incomingSynonyms) throws Exception {

        // load synonyms in RGD
        List<TermSynonym> inRgdSynonyms = new ArrayList<>();
        String[] goOntIds = {"BP", "CC", "MF"};
        for( String goOntId: goOntIds ) {
            inRgdSynonyms.addAll(dao.getActiveSynonymsByType(goOntId, "never_in_taxon"));
            inRgdSynonyms.addAll(dao.getActiveSynonymsByType(goOntId, "only_in_taxon"));
            inRgdSynonyms.addAll(dao.getActiveSynonymsByName(goOntId, "Not4Curation"));
        }

        List<TermSynonym> synonymsForInsert = ListUtils.subtract(incomingSynonyms, inRgdSynonyms);
        List<TermSynonym> synonymsForDelete = ListUtils.subtract(inRgdSynonyms, incomingSynonyms);

        List<TermSynonym> synonymsMatching = ListUtils.intersection(incomingSynonyms, inRgdSynonyms);

        // check if matching synonyms have syn_key set
        int synKeySet = 0;
        for( TermSynonym tsyn: synonymsMatching ) {
            if( tsyn.getKey()!=0 ) {
                synKeySet++;
            }
        }
        if( synKeySet < synonymsMatching.size() ) {
            throw new Exception("unexpected: matching synonyms in RGD without SYN_KEY set");
        }
        dao.updateTermSynonymLastModifiedDate(synonymsMatching);

        // insert synonyms
        List<TermSynonym> synonymsInserted = new ArrayList<>();
        for( TermSynonym syn: synonymsForInsert ) {

            // NOTE: we download taxon constraints file from live github repo
            //       and there is a chance that some new GO terms did not end up in the official
            //       GO snapshot release file (i.e. the GO term is not officially released yet)
            Term t = dao.getTerm(syn.getTermAcc());
            if( t != null ) {
                if( t.isObsolete() ) {
                    logger.warn(" TaxonConstraint: term "+syn.getTermAcc()+" is obsolete -- synonym insertion ignored");
                } else {
                    dao.insertTermSynonym(syn, "GO");
                    synonymsInserted.add(syn);
                }
            } else {
                logger.warn(" TaxonConstraint: term "+syn.getTermAcc()+" not present in GO obo file -- synonym insertion ignored");
            }
        }

        // delete term synonyms scheduled for delete if and only if they are older than 10 days
        Date tenDaysEarlier = Utils.addDaysToDate(new Date(), -10);
        List<TermSynonym> obsoleteSynonyms = new ArrayList<>();
        for( TermSynonym tsyn: synonymsForDelete ) {
            if( tsyn.getLastModifiedDate().before(tenDaysEarlier) ) {
                obsoleteSynonyms.add(tsyn);
            }
        }
        logStatus.info("synonyms for delete: "+synonymsForDelete.size()+", but only "+obsoleteSynonyms.size()+" are obsolete (not modified in the last 10 days) and only those will be deleted");
        dao.deleteTermSynonyms(obsoleteSynonyms);

        printStats("inserted", synonymsInserted);
        printStats("deleted", obsoleteSynonyms);
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
                logger.info("ERROR: unexpected synonym type / name");
            }
        }
        String msg;
        if( countNeverInTaxon>0 ) {
            msg = "  never_in_taxon synonyms "+title+": "+countNeverInTaxon;
            logger.info(msg);
        }
        if( countOnlyInTaxon>0 ) {
            msg = "  only_in_taxon synonyms "+title+": "+countOnlyInTaxon;
            logger.info(msg);
        }
        if( countNot4Curation>0 ) {
            msg = "  Not4Curation synonyms "+title+": "+countNot4Curation;
            logger.info(msg);
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

    public String getOnlyInTaxonFile() {
        return onlyInTaxonFile;
    }

    public void setOnlyInTaxonFile(String onlyInTaxonFile) {
        this.onlyInTaxonFile = onlyInTaxonFile;
    }

    public String getNeverInTaxonFile() {
        return neverInTaxonFile;
    }

    public void setNeverInTaxonFile(String neverInTaxonFile) {
        this.neverInTaxonFile = neverInTaxonFile;
    }

    public String getTaxonUnionOboFile() {
        return taxonUnionOboFile;
    }

    public void setTaxonUnionOboFile(String taxonUnionOboFile) {
        this.taxonUnionOboFile = taxonUnionOboFile;
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

        logStatus.debug(termAcc+" "+taxon);

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
}
