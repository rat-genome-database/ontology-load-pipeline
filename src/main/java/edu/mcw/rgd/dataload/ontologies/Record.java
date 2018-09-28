package edu.mcw.rgd.dataload.ontologies;

import edu.mcw.rgd.datamodel.ontologyx.Relation;
import edu.mcw.rgd.datamodel.ontologyx.Term;
import edu.mcw.rgd.datamodel.ontologyx.TermSynonym;
import edu.mcw.rgd.datamodel.ontologyx.TermXRef;
import edu.mcw.rgd.pipelines.PipelineRecord;
import edu.mcw.rgd.pipelines.PipelineSession;
import edu.mcw.rgd.process.Utils;
import org.apache.commons.collections.ListUtils;
import org.apache.log4j.Logger;

import java.util.*;

/**
 * Created by IntelliJ IDEA.
 * User: mtutaj
 * Date: 12/28/10
 * Time: 11:52 AM
 */
public class Record extends PipelineRecord {

    private static int recno = 0;
    private Term term = new Term();

    // FLAGS
    // "INSERT";  this term needs to be inserted into db
    // "MATCH";   we have this term in database, do not do anything
    // "UPDATE";  term properties are to be updated

    private List<TermSynonym> synonyms = new ArrayList<>(); // incoming synonyms
    public SynonymManager synonymManager = new SynonymManager();

    private Map<String, Relation> edges = new HashMap<>(); // map term_acc to edge type

    // cyclic relationships are always loaded into database as synonyms
    // non-cyclic relationships are loaded as relationships
    private static Set<String> cyclicRelationships = new HashSet<>();

    public XRefManager xrefManager = new XRefManager();

    public String oboText;

    static int termNamesOverridden = 0;
    static int termDefsOverridden = 0;
    static int edgesAdded = 0;
    static int xrefsAdded = 0;

    static public void dumpMergeStats() {
        if( termNamesOverridden+termDefsOverridden+edgesAdded+xrefsAdded == 0 ) {
            return;
        }
        System.out.println("MERGE STATS:");
        System.out.println("  term names overridden: "+termNamesOverridden);
        System.out.println("  term defs overridden : "+termDefsOverridden);
        System.out.println("  term relations added : "+edgesAdded);
        System.out.println("  term xrefs added     : "+xrefsAdded);
    }

    public Record() {
        setRecNo(++recno);
    }

    public void addIncomingXRefs(List<TermXRef> xrefs) {
        xrefManager.addIncomingXRefs(xrefs);
    }

    public void qcXRefs(OntologyDAO dao) throws Exception {
        List<TermXRef> inRgdXRefs = dao.getTermXRefs(term.getAccId());
        xrefManager.qc(term.getAccId(), inRgdXRefs);
    }

    public void loadXRefs(OntologyDAO dao, PipelineSession session) throws Exception {

        if( !xrefManager.getMatchingXRefs().isEmpty() ) {
            session.incrementCounter("DBXREFS_MATCHING", xrefManager.getMatchingXRefs().size());
        }

        if( !xrefManager.getForInsertXRefs().isEmpty() ) {
            dao.insertTermXRefs(xrefManager.getForInsertXRefs());
            session.incrementCounter("DBXREFS_INSERTED", xrefManager.getForInsertXRefs().size());
        }

        if( !xrefManager.getForDeleteXRefs().isEmpty() ) {
            dao.deleteTermXRefs(xrefManager.getForDeleteXRefs());
            session.incrementCounter("DBXREFS_DELETED", xrefManager.getForDeleteXRefs().size());
        }

        if( !xrefManager.getDescChangedXRefs().isEmpty() ) {
            dao.updateTermXRefDescriptions(xrefManager.getDescChangedXRefs());
            session.incrementCounter("DBXREFS_DESCRIPTON_CHANGED", xrefManager.getDescChangedXRefs().size());
        }
    }

    /**
     * return true if given relationships in given ontology is cyclic
     * @param ontId ontology id
     * @param relationship relationship id
     * @return true if given relationships in given ontology is cyclic
     */
    synchronized public static boolean isCyclicRelationship(String ontId, String relationship) {

        String key = ontId+":"+relationship;
        return cyclicRelationships.contains(key);
    }

    /**
     * add new cyclic relationship for given ontology
     * @param ontId ontology id
     * @param relationship relationship id
     */
    synchronized public static void addCyclicRelationship(String ontId, String relationship) {

        System.out.println("Relationship "+relationship+" for ontology "+ontId+" is cyclic");
        String key = ontId+":"+relationship;
        cyclicRelationships.add(key);
    }

    public void addSynonym(TermSynonym syn) {

        // do not add "." synonyms
        if( syn.getName().equals(".") )
            return;

        // for CHEBI, do not add synonyms like '0', '0.0', '-1', '123.456' etc
        if( isChebiExcludedSynonym(syn) ) {
            return;
        }

        // synonym name must be different than its accession id
        if( Utils.stringsAreEqual(syn.getName(), syn.getTermAcc()) ) {
            return;
        }

        if( !synonyms.contains(syn) ) {
            synonyms.add(syn);
        }
    }

    // for chebi ontology, do not add synonyms that look like numbers: f.e. '412.345', '0', '0.0', '-2' etc
    // returns true, if this synonym should be skipped from adding to incoming synonyms
    boolean isChebiExcludedSynonym( TermSynonym syn ) {
        if( !Utils.defaultString(syn.getTermAcc()).startsWith("CHEBI") ) {
            return false;
        }
        try {
            Double.parseDouble(syn.getName());
            // it was a Double -- this synonym is banned
            return true;
        } catch(NumberFormatException e){
            return false; // not a Double --> valid synonym
        }
    }

    public void addSynonym(String name, String type) {

        // synonyms with missing name must be skipped
        if( name==null )
            return;

        TermSynonym synonym = new TermSynonym();
        synonym.setName(name.trim());
        synonym.setType(type);
        synonym.setTermAcc(term.getAccId());

        // CTD.obo could have suffix ", INCLUDED" in the last part of synonym --
        //   the suffix has to be removed and the synonym type is to be narrow
        int includedPos = synonym.getName().lastIndexOf(", INCLUDED");
        if( includedPos>0 ) {
            synonym.setName(synonym.getName().substring(0, includedPos).trim());
            synonym.setType("narrow_synonym");
        }

        convertSynonymType(synonym);

        if( !extractDbXrefs(synonym) )
            return;

        // synonyms with name "INCLUDED" should be skipped -- happens in CTD.obo
        if( synonym.getName().equals("INCLUDED") )
            return;

        // if synonym has ';' in the name, it could be split into two or more synonyms
        if( splitSynonyms(synonym.getName(), synonym.getType()) )
            return;

        // CTD.obo could have suffix ", FORMERLY" in the last part of synonym --
        //   the suffix has to be removed;
        includedPos = synonym.getName().lastIndexOf(", FORMERLY");
        if( includedPos>0 ) {
            synonym.setName(synonym.getName().substring(0, includedPos).trim());
        }

        // some synonyms from NBO ontology are malformed: "stationary movement EXACT []" EXACT []
        // this extra step removes secondary 'EXACT []'
        includedPos = synonym.getName().lastIndexOf(" EXACT []");
        if( includedPos >= 0 ) {
            synonym.setName(synonym.getName().replace(" EXACT []", ""));
        }

        // strip starting unmatching double quotes
        int quotePos1 = synonym.getName().indexOf('\"');
        int quotePos2 = synonym.getName().lastIndexOf('\"');
        if( quotePos1==0 && quotePos2==0 ) {
            synonym.setName(synonym.getName().substring(1));
        }

        // special processing for CAS Registry Numbers
        if( type.equals("xref") && synonym.getName().endsWith("\"CAS Registry Number\"") ) {
            // extract CasRN
            int pos1 = synonym.getName().indexOf(':');
            int pos2 = synonym.getName().indexOf('\"');
            if( pos1>0 && pos2>0 && pos1<pos2 ) {
                // create a new synonym with type 'xref_casrn' and a value set to "CAS Registry Number"
                String CasRN = synonym.getName().substring(pos1+1, pos2).trim();
                TermSynonym casrnSyn = new TermSynonym();
                casrnSyn.setName(CasRN);
                casrnSyn.setType("xref_casrn");
                casrnSyn.setTermAcc(term.getAccId());
                addSynonym(casrnSyn);
            }
        }

        addSynonym(synonym); // if synonym name is not empty, add the synonym
    }

    boolean splitSynonyms(String name, String type) {

        // do synonym splits only for RDO ontology
        if( !this.term.getOntologyId().equals("RDO") )
            return false;

        // fix for RDO ontology:
        // replace ', FORMERLY ' with '; '
        int formerlyPos = name.indexOf(", FORMERLY ");
        if( formerlyPos > 0 ) {
            name = name.replace(", FORMERLY ", "; ");
        }

        // ';' separates different synonyms
        int semicolonPos = name.indexOf(';');
        if( semicolonPos < 0 )
            return false; // no splitting

        // many times semicolon don't separates synonyms when used within parentheses:
        // 'Supernumerary der(22)t(11;22) syndrome'
        int leftParenthesisPos = name.lastIndexOf('(', semicolonPos-1);
        int rightParenthesisPos = name.indexOf(')', semicolonPos+1);
        if( leftParenthesisPos>=0 && leftParenthesisPos<semicolonPos && rightParenthesisPos>semicolonPos ) {
            return false;
        }

        // do the splitting now
        addSynonym(name.substring(0, semicolonPos), type);
        addSynonym(name.substring(semicolonPos+1), type);
        return true;
    }


    // return true if synonym type was converted
    boolean convertSynonymType(TermSynonym syn) {

        if( !syn.getType().endsWith("synonym") )
            return false;
        String synName = syn.getName();

        // preprocessing of regular synonyms:
        // convert lines like 'synonym: "hip girth" RELATED []'
        // into               'related_synonym: "hip girth" []'
        String[] types = {"RELATED ", "EXACT ", "BROAD ", "NARROW "};
        for( String type: types ) {

            int pos = synName.lastIndexOf(type);
            if( pos > 0 ) {
                // 'RELATED ' was found in the middle of synonym name
                syn.setName(synName.substring(0, pos) + synName.substring(pos+type.length()).trim()); // remove 'RELATED ' from synonym name
                if( syn.getType().equals("synonym") )
                    syn.setType(type.trim().toLowerCase()+"_synonym"); // change synonym type
                return true;
            }
            else if( pos == 0 ) {
                // 'RELATED ' was found at the beginning of synonym name
                syn.setName(synName.substring(pos+type.length()).trim()); // remove 'RELATED ' from synonym name
                if( syn.getType().equals("synonym") )
                    syn.setType(type.trim().toLowerCase()+"_synonym"); // change synonym type to 'related_synonym'
                return true;
            }
        }
        return false;
    }

    // convert synonym name like '"hip girth" []'
    //                        or '"asta la vista" [GO:mp, GOC:curators]'
    // into pair (name, dbxrefs) = ('hip girth', '')
    //                           = ('asta la vista', 'GO:mp, GOC:curators')
    boolean extractDbXrefs(TermSynonym syn) {

        // get dbxrefs between [] at the end of synonym name
        String name = syn.getName();
        int startPos = name.lastIndexOf('[');
        int endPos = name.lastIndexOf(']');
        if( startPos>=0 && endPos>=0 && startPos<endPos && endPos==name.length()-1 ) {
            // there is a dbxref, possibly empty
            if( startPos+1<endPos ) {
                // non-empty db xrefs available
                syn.setDbXrefs(name.substring(startPos+1, endPos).trim());
            }
            name = name.substring(0, startPos).trim();
        }

        // no dbxref -- just extract contents of the double quotes
        endPos = name.lastIndexOf('\"');
        startPos = name.indexOf('\"');
        if( startPos==0 && endPos>0 ) {
            // the contents between double quotes is the new synonym name
            name = name.substring(startPos+1, endPos).trim();
        }
        else {
            // no double quotes found
            // or there is some contents before the double quotes -- use entire string
            name = name.trim();
        }

        if( name.length()==0 ) {
            System.out.println("Empty synonym name for term "+term.getAccId());
            return false;
        }
        syn.setName(name);
        return true;
    }

    /**
     * run qc for synonyms
     * @param synonymsInRgd list of synonyms in rgd
     * @param session pipeline session
     * @throws Exception if something wrong happens in spring framework
     */
    public void qcSynonyms( List<TermSynonym> synonymsInRgd, PipelineSession session) throws Exception {

        synonymManager.qc(term.getTerm(), synonymsInRgd, this.synonyms);

        // exception 1: Reactome xref_analog synonyms in RGD should be synced with Reactome synonyms in incoming data
        for( TermSynonym syn: synonymsInRgd ) {
            if( syn.getType().equals("xref_analog") && syn.getName().contains("Reactome") ) {
                if( !getSynonyms().contains(syn) ) { // 'syn' is in RGD, it must NOT be in incoming data
                    if( !getSynonymsForDelete().contains(syn) ) {
                        getSynonymsForDelete().add(syn);
                    }
                }
            }
        }

        session.incrementCounter("SYNONYMS_MATCHED", synonymManager.matchingSynonyms.size());

        // special QC for RS synonyms starting with 'RGD':
        //  to make strain to RS-term association possible, RS-term must have a synonym in form 'RGD ID: strainRgdId'
        if( getTerm().getOntologyId().equals("RS") ) {
            List<TermSynonym> synonyms = new ArrayList<>(synonymManager.matchingSynonyms);
            synonyms.addAll(synonymManager.forInsertSynonyms);
            for( TermSynonym tsyn: synonyms ) {
                if( tsyn.getName().startsWith("RGD") ) {
                    if( !tsyn.getName().startsWith("RGD ID: ") ) {
                        Logger log = Logger.getLogger("malformedRsSynonyms");
                        log.warn(tsyn.getTermAcc() + " " + tsyn.getName());
                    }
                }
            }
        }
    }

    /**
     * merge the current record with the new record
     * @param r Record object to be merged with
     */
    public void merge(Record r) {
        boolean changed = false;

        // term name + def
        if( !Utils.stringsAreEqualIgnoreCase(this.getTerm().getTerm(), r.getTerm().getTerm())
         && !Utils.defaultString(r.getTerm().getTerm()).isEmpty() ) { // don't nullify term name
            this.getTerm().setTerm(r.getTerm().getTerm());
            termNamesOverridden++;
            changed = true;
            Logger.getRootLogger().debug("***MERGE term name change "+term.getAccId()+" "+term.getTerm());
        }
        if( !Utils.stringsAreEqualIgnoreCase(this.getTerm().getDefinition(), r.getTerm().getDefinition())
                && !Utils.defaultString(r.getTerm().getDefinition()).isEmpty() ) { // don't nullify term definition
            this.getTerm().setDefinition(r.getTerm().getDefinition());
            termDefsOverridden++;
            changed = true;
            Logger.getRootLogger().debug("***MERGE term def change "+term.getAccId()+" "+term.getTerm());
        }

        // merge synonyms
        List<TermSynonym> newSynonyms = ListUtils.subtract(r.getSynonyms(), this.getSynonyms());
        if( !newSynonyms.isEmpty() ) {
            this.getSynonyms().addAll(newSynonyms);
            //synonymsAdded += newSynonyms.size();
            changed = true;
            //System.out.println("***MERGE new synonyms "+term.getAccId()+" "+term.getTerm());
        }

        // add new xrefs
        int newXrefs = mergeXRefs(r);
        if( newXrefs!=0 ) {
            xrefsAdded += newXrefs;
            changed = true;
            Logger.getRootLogger().debug("***MERGE new xrefs "+term.getAccId()+" "+term.getTerm());
        }

        // edges
        int newEdges = 0;
        try {
            newEdges = mergeEdges(r);
        }catch(Exception e) {
            System.out.println("edge problem");
        }
        if( newEdges!=0 ) {
            edgesAdded += newEdges;
            changed = true;
            Logger.getRootLogger().debug("***MERGE new edges "+term.getAccId()+" "+term.getTerm());
        }

        if( !changed ) {
            System.out.println("MERGE no changes "+term.getAccId()+" "+term.getTerm());
        } else {
            //System.out.println("***MERGE changes "+term.getAccId()+" "+term.getTerm());
        }
    }

    int mergeXRefs(Record r) {
        if( r.xrefManager==null || r.xrefManager.getIncomingXRefs()==null ) {
            return 0; // no incoming xrefs, nothing to merge
        }
        if( this.xrefManager==null || this.xrefManager.getIncomingXRefs()==null ) {
            this.xrefManager = r.xrefManager;
            return r.xrefManager.getIncomingXRefs().size();
        } else {
            List<TermXRef> newXRefs = ListUtils.subtract(r.xrefManager.getIncomingXRefs(), this.xrefManager.getIncomingXRefs());
            xrefManager.getIncomingXRefs().addAll(newXRefs);
            return newXRefs.size();
        }
    }

    int mergeEdges(Record r) {
        List<String> currentEdges = new ArrayList<>();
        for( Map.Entry<String,Relation> entry: this.edges.entrySet() ) {
            currentEdges.add(entry.getKey()+"|"+entry.getValue().name());
        }

        List<String> overrideEdges = new ArrayList<>();
        for( Map.Entry<String,Relation> entry: r.edges.entrySet() ) {
            overrideEdges.add(entry.getKey()+"|"+entry.getValue().name());
        }

        List<String> newEdges = ListUtils.subtract(overrideEdges, currentEdges);
        if( !newEdges.isEmpty() ) {
            for( String edge: newEdges ) {
                String parentTermAcc = edge.substring(0, 11);
                // skip self-referencing edges
                if( !parentTermAcc.equals(getTerm().getAccId()) ) {
                    String relName = edge.substring(12);
                    Relation relation = Relation.valueOf(relName);
                    this.edges.put(parentTermAcc, relation);
                }
            }
        }
        return newEdges.size();
    }

    /**
     * get term synonyms read from incoming data
     * @return Set of OntTermSynonym objects
     */
    public List<TermSynonym> getSynonyms() {
        return synonyms;
    }

    public Collection<TermSynonym> getSynonymsForInsert() {
        return synonymManager.forInsertSynonyms;
    }

    public Collection<TermSynonym> getSynonymsForDelete() {
        return synonymManager.forDeleteSynonyms;
    }

    public Collection<TermSynonym> getSynonymsForUpdate() {
        return synonymManager.matchingSynonyms;
    }

    /// return true of a new edge was added; false if the edge to be added already was in the edge map
    public boolean addEdge(String parentTermAcc, Relation rel) {
        // never add an edge where parent and the term are the same!
        if( !parentTermAcc.equals(term.getAccId()) ) {
            return edges.put(parentTermAcc, rel)==null;
        }
        return false;
    }

    /// return true if the to-be-deleted edge was in the edge map and it was removed
    public boolean deleteEdge(String parentTermAcc) {
        return edges.remove(parentTermAcc) != null;
    }

    public Map<String, Relation> getEdges() {
        return edges;
    }

    public Term getTerm() {
        return term;
    }

    public void setTerm(Term term) {
        this.term = term;
    }
}
