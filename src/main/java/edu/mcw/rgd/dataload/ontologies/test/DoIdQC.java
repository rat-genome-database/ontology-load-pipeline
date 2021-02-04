package edu.mcw.rgd.dataload.ontologies.test;

import edu.mcw.rgd.dataload.ontologies.OntologyDAO;
import edu.mcw.rgd.dataload.ontologies.TermStats;
import edu.mcw.rgd.dataload.ontologies.XRefManager;
import edu.mcw.rgd.datamodel.ontologyx.Ontology;
import edu.mcw.rgd.datamodel.ontologyx.Term;
import edu.mcw.rgd.datamodel.ontologyx.TermSynonym;
import edu.mcw.rgd.datamodel.ontologyx.TermXRef;
import edu.mcw.rgd.process.Utils;

import java.io.*;
import java.util.*;

/**
 * Compare incoming DO ontology file against RGD. Determine:
 * <ol>
 *     <li>acc ids that are in incoming file, but not in RGD</li>
 *     <li>acc ids that are not in incoming file, or obsolete, but that are active in RGD</li>
 *     <li>term name changes</li>
 *     <li>OMIM:PS assignments</li>
 *     <li>OMIM ids that are inactive in OMIM</li>
 * </ol>
 */
public class DoIdQC {

    OntologyDAO dao = new OntologyDAO();
    BufferedWriter synQcFile;

    int termsWithoutDef = 0;
    int termsWithNewDef = 0;
    int termsWithSameDef = 0;
    int termsWithUpdatedDef = 0;
    int termsWithIncompatibleDef = 0;
    int missingParents = 0;
    int omimPsIdsInserted = 0;
    int omimPsIdsUpToDate = 0;
    int omimPsIdsMultiple = 0;
    int omimIdsNotInRdo = 0;
    int insertedSynonyms = 0;
    int deletedSynonyms = 0;
    int suppressedSynonyms = 0;

    int xrefsMatched = 0;
    int xrefsInserted = 0;
    int xrefsDeleted = 0;
    int xrefsUpdated = 0;

    public static void main(String[] args) throws Exception {

        // https://raw.githubusercontent.com/DiseaseOntology/HumanDiseaseOntology/master/src/ontology/releases/2018-05-15/doid.obo
        String fileName = "h:/do/20210128_doid.obo";
        String synQcFileName = "/tmp/do_synonym_qc.log";

        new DoIdQC().run(fileName, synQcFileName);
    }

    void run(String fileName, String synQcFileName) throws Exception {

        synQcFile = new BufferedWriter(new FileWriter(synQcFileName));

        Map<String, OboTerm> oboTerms = parseOboFile(fileName);

        // load accession ids for DO terms excluding RGD custom terms
        Ontology ont = dao.getOntology("RDO");
        List<String> allDoIds = dao.getAllActiveTermDescendantAccIds(ont.getRootTermAcc());
        allDoIds.add(ont.getRootTermAcc());

        Map<String,Object> idsInRgd = new HashMap<>();
        for( String allDoId: allDoIds ) {
            // filter out custom RDO terms
            if( allDoId.startsWith("DOID:9") && allDoId.length()==12 ) {
                continue;
            }
            idsInRgd.put(allDoId, allDoId);
        }

        int oboObsoleteCount = 0;
        int oboTermsNotInRgd = 0;
        int oboTermNameChanged = 0;
        List<OboTerm> oboTermsRandom = new ArrayList<>(oboTerms.values());
        Collections.shuffle(oboTermsRandom);
        for( OboTerm oboTerm: oboTermsRandom ) {
            if( oboTerm.isObsolete ) {
                oboObsoleteCount++;
                continue;
            }
            if( !idsInRgd.containsKey(oboTerm.id) ) {
                System.out.println("NOT IN RGD: "+oboTerm.id);
                oboTermsNotInRgd++;
            }

            TermStats ts = dao.getTermWithStats(oboTerm.id);
            if( ts.term!=null && Utils.stringsCompareToIgnoreCase(ts.term.getTerm(), oboTerm.name)!=0 ) {
                System.out.println("NAME CHANGE: "+oboTerm.id+" ["+oboTerm.name+"]");
                oboTermNameChanged++;
                continue;
            }

            if( ts.term!=null ) {
                qcDef(oboTerm.def, ts.term);
                qcDefXRefs(oboTerm.xrefs, ts.term);
                qcRels(oboTerm.parentAccIds, ts.term);
                qcOmimPs(oboTerm.omimPSIds, ts.term);
                qcSynonyms(oboTerm, ts.term);
            }
        }
        synQcFile.close();

        // check which term are active in RGD, but obsolete in obo file
        for( String idInRgd: idsInRgd.keySet() ) {
            OboTerm oboTerm = oboTerms.get(idInRgd);
            if( oboTerm == null ) {
                System.out.println("ACTIVE ID IN RGD, NOT PRESENT IN OBO: "+idInRgd);
                continue;
            }
            if( oboTerm.isObsolete ) {
                System.out.println("ACTIVE ID IN RGD, OBSOLETE IN OBO: "+idInRgd);
            }
        }
        System.out.println("OBO TERM COUNT "+oboTerms.size());
        System.out.println("OBO OBSOLETE TERM COUNT "+oboObsoleteCount);
        System.out.println("OBO TERMS NOT IN RGD "+oboTermsNotInRgd);
        System.out.println("OBO TERMS WITH NAME CHANGED "+oboTermNameChanged);
        System.out.println("");
        System.out.println("OBO TERMS WITHOUT DEF "+termsWithoutDef);
        System.out.println("OBO TERMS WITH NEW DEF "+termsWithNewDef);
        System.out.println("OBO TERMS WITH SAME DEF "+termsWithSameDef);
        System.out.println("OBO TERMS WITH UPDATED DEF "+termsWithUpdatedDef);
        System.out.println("OBO TERMS WITH INCOMPATIBLE DEF "+termsWithIncompatibleDef);
        System.out.println("");
        System.out.println("MISSING PARENTS: "+missingParents);
        if( omimPsIdsInserted!=0 ) {
            System.out.println("OMIM:PS ids inserted: " + omimPsIdsInserted);
        }
        System.out.println("OMIM:PS ids up-to-date: "+omimPsIdsUpToDate);
        if( omimPsIdsMultiple!=0 ) {
            System.out.println("OMIM:PS ids assigned to multiple terms: " + omimPsIdsMultiple);
        }
        System.out.println("OMIM IDs not in RDO: "+omimIdsNotInRdo);

        System.out.println("");
        System.out.println("synonyms inserted: "+insertedSynonyms);
        System.out.println("synonyms deleted: "+deletedSynonyms+"    -- ICD10CM:xxx and ICD9CM:xxx");
        System.out.println("synonyms suppressed: "+suppressedSynonyms);

        System.out.println("");
        if( xrefsMatched!=0 ) {
            System.out.println("def xrefs matched: " + xrefsMatched);
        }
        if( xrefsInserted!=0 ) {
            System.out.println("def xrefs inserted: " + xrefsInserted);
        }
        if( xrefsDeleted!=0 ) {
            System.out.println("def xrefs deleted: " + xrefsDeleted);
        }
        if( xrefsUpdated!=0 ) {
            System.out.println("def xrefs updated: " + xrefsUpdated);
        }
    }

    Map<String, OboTerm> parseOboFile(String fileName) throws Exception {

        Set<String> omimIdsInRdo = dao.getOmimIdsInRdo();
        System.out.println("OMIM IDs in RDO: "+omimIdsInRdo.size());

        BufferedReader reader = new BufferedReader(new FileReader(fileName));
        Map<String, OboTerm> oboTerms = new HashMap<>();
        String line;
        OboTerm oboTerm = null;
        while( (line=reader.readLine())!=null ) {

            // handle term boundary
            if( line.equals("[Term]") ) {
                if( oboTerm!=null ) {
                    oboTerms.put(oboTerm.id, oboTerm);
                }
                continue;
            }

            // handle end of term region
            if( line.equals("[Typedef]") ) {
                // handle last term
                if( oboTerm!=null ) {
                    oboTerms.put(oboTerm.id, oboTerm);
                }
                break;
            }

            if( line.startsWith("id: ") ) {
                oboTerm = new OboTerm();
                oboTerm.id = line.substring(4).trim();
            }
            else if( line.startsWith("name: ") ) {
                oboTerm.name = line.substring(6).trim().replace("  "," ");
            }
            else if( line.startsWith("def: ") ) {
                String def = line.substring(5).trim();
                oboTerm.def = parseDef(def);
                oboTerm.xrefs = parseDefXrefs(def);
            }
            else if( line.startsWith("is_a: ") ) {
                String parentAccId = parseRel(line.substring(6).trim());
                if( parentAccId!=null ) {
                    oboTerm.parentAccIds.add(parentAccId);
                }
            }
            else if( line.startsWith("xref: OMIM:PS") ) {
                String omimPsId = line.substring(6).trim();
                oboTerm.omimPSIds.add(omimPsId);
            }
            else if( line.startsWith("synonym: ") ) {
                oboTerm.parseSynonym(line.substring(9).trim());
            }
            else if( line.startsWith("xref: GARD:") ||
                     line.startsWith("xref: ORDO:") ||
                     line.startsWith("xref: ICD-O:") ||
                     line.startsWith("xref: ICD10CM:") ||
                     line.startsWith("xref: ICD9CM:") ||
                     line.startsWith("xref: NCI:")) {
                oboTerm.parseXrefSynonym(line.substring(6).trim());
            }
            else if( line.startsWith("xref: OMIM:") ) {
                String omimId = line.substring(6).trim();
                if( dao.isOmimIdInactive(omimId) ) {
                    System.out.println("obsolete "+omimId+" for term "+oboTerm.id+" "+oboTerm.name);
                }
                if( !omimIdsInRdo.contains(omimId) ) {
                    System.out.println("WARNING: "+omimId+" NOT IN RGD!");
                    omimIdsNotInRdo++;
                }
            }
            else if( line.startsWith("is_obsolete: ") ) {
                if( line.contains("true") ) {
                    oboTerm.isObsolete = true;
                }
            }
        }

        reader.close();

        System.out.println(fileName+" parsed!");

        return oboTerms;
    }

    String parseDef(String line) {

        Integer[] startStopPos = new Integer[2];
        if( !parseQuotedString(startStopPos, line) ) {
            System.out.println("*** unexpected def: double quote parsing error");
            return line;
        }

        String def = line.substring(startStopPos[0]+1, startStopPos[1]).replace("\\\"", "\"").trim();
        return def;
    }

    // "A malignant vascular tumor ..." [PMID:23327728, url:http\://emedicine.medscape.com/article/276512-overview, url:http\://en.wikipedia.org/wiki/Hemangiosarcoma]
    List<TermXRef> parseDefXrefs(String line) {
        int squareBracketEnd = line.lastIndexOf(']');
        int squareBracketStart = line.lastIndexOf('[');
        String xrefStr = line.substring(squareBracketStart+1, squareBracketEnd);
        if( xrefStr.isEmpty() ) {
            return null;
        }

        List<TermXRef> incomingXrefs = new ArrayList<>();
        String[] xrefs = xrefStr.split("\\,\\ ");
        for( String xref: xrefs ) {
            String value = xref.replace("\\:", ":");
            if( value.indexOf(':')<0 ) {
                System.out.println("invalid def xref: ["+xref+"]");
                continue;
            }

            // replace 'url:http\://en.wikipedia.org/wiki/Hemangiosarcoma' with 'http://en.wikipedia.org/wiki/Hemangiosarcoma'
            if( value.startsWith("url:") ) {
                // ensure there is a second ':'
                int colon2pos = value.indexOf(':', 4);
                if( colon2pos>0 ) {
                    value = value.substring(4);
                }
            }

            TermXRef x = new TermXRef();
            x.setXrefDescription("DO");

            // commas could be escaped in the source -- unescape it
            String unescapedValue = value.replace("\\,", ",");
            // also, value should not end with a comma (looks like a typo)
            if( unescapedValue.endsWith(",") ) {
                unescapedValue = unescapedValue.substring(0, unescapedValue.length()-1);
            }
            //if( unescapedValue.length()!= value.length() ) {
            //    System.out.println("unescaped");
            //}
            x.setXrefValue(unescapedValue);

            incomingXrefs.add(x);
        }
        return incomingXrefs;
    }

    static boolean parseQuotedString(Integer[] startStopPos, String line) {

        int quotePos1 = line.indexOf('\"');
        if( quotePos1 < 0 ) {
            return false;
        }
        int quotePos2 = quotePos1 + 1;
        while( true ) {
            quotePos2 = line.indexOf('\"', quotePos2);
            if( quotePos2<0 ) {
                return false;
            }
            if( line.charAt(quotePos2-1) == '\\' ) {
                quotePos2++;
                continue;
            }

            startStopPos[0] = quotePos1;
            startStopPos[1] = quotePos2;
            return true;
        }
    }

    String parseRel( String line ) {
        int prefixPos = line.indexOf("DOID:");
        if( prefixPos<0 ) {
            if( !line.startsWith("DOID:") ) {
                return null;
            }
            throw new RuntimeException("parseRel error");
        }
        int spacePos = line.indexOf(' ', prefixPos+1);
        return line.substring(prefixPos, spacePos);
    }

    void qcDef( String oboDef, Term termInRgd ) throws Exception {

        if( Utils.isStringEmpty(oboDef) ) {
            termsWithoutDef++;
            return;
        }

        // in-rgd term has no def -- fill it up
        if( Utils.isStringEmpty(termInRgd.getDefinition()) ) {
            oboDef += " (DO)";
            termInRgd.setDefinition(oboDef);
            dao.updateTerm(termInRgd);
            termsWithNewDef++;
            return;
        }

        // in-rgd term has the same def as incoming obo term
        if( Utils.stringsCompareToIgnoreCase(oboDef, termInRgd.getDefinition())==0 ) {
            oboDef += " (DO)";
            termInRgd.setDefinition(oboDef);
            dao.updateTerm(termInRgd);
            termsWithSameDef++;
            return;
        }
        oboDef += " (DO)";
        if( Utils.stringsCompareToIgnoreCase(oboDef, termInRgd.getDefinition())==0 ) {
            termsWithSameDef++;
            return;
        }

        // different def -- update def only for DO
        if( termInRgd.getDefinition().endsWith(" (DO)") ) {
            termInRgd.setDefinition(oboDef);
            dao.updateTerm(termInRgd);
            termsWithUpdatedDef++;
            return;
        }

        termsWithIncompatibleDef++;
    }

    void qcDefXRefs( List<TermXRef> incomingXRefs, Term termInRgd ) throws Exception {

        if( incomingXRefs==null || incomingXRefs.isEmpty() ) {
            return;
        }

        XRefManager xrefManager = new XRefManager();
        xrefManager.addIncomingXRefs(incomingXRefs);

        // filter out any xrefs with source other than DO
        List<TermXRef> inRgdXrefs = dao.getTermXRefs(termInRgd.getAccId());
        Iterator<TermXRef> it = inRgdXrefs.iterator();
        while( it.hasNext() ) {
            TermXRef xref = it.next();
            if( !Utils.stringsAreEqual(xref.getXrefDescription(), "DO") ) {
                it.remove();
            }
        }

        xrefManager.qc(termInRgd.getAccId(), inRgdXrefs);

        if( !xrefManager.getMatchingXRefs().isEmpty() ) {
            xrefsMatched += xrefManager.getMatchingXRefs().size();
        }

        if( !xrefManager.getForInsertXRefs().isEmpty() ) {
            dao.insertTermXRefs(xrefManager.getForInsertXRefs());
            xrefsInserted += xrefManager.getForInsertXRefs().size();
        }

        if( !xrefManager.getForDeleteXRefs().isEmpty() ) {
            dao.deleteTermXRefs(xrefManager.getForDeleteXRefs());
            xrefsDeleted += xrefManager.getForDeleteXRefs().size();
        }

        if( !xrefManager.getDescChangedXRefs().isEmpty() ) {
            dao.updateTermXRefDescriptions(xrefManager.getDescChangedXRefs());
            xrefsUpdated += xrefManager.getDescChangedXRefs().size();
        }
    }

    void qcRels(Collection<String> oboParentAccIds, Term termInRgd) throws Exception {

        Set<String> parentAccIdsInRgd = new HashSet<>(dao.getActiveParentTerms(termInRgd.getAccId()));

        for( String oboParentAccId: oboParentAccIds ) {
            if( !parentAccIdsInRgd.contains(oboParentAccId) ) {
                // see if obo acc id is on one of the paths of in-rgd relationships
                boolean missingParent = true;
                for( String inRgdParentAccId: parentAccIdsInRgd ) {
                    if( dao.isDescendantOf(inRgdParentAccId, oboParentAccId) ) {
                        missingParent = false;
                        break;
                    }
                }

                if( missingParent ) {
                    System.out.println(termInRgd.getAccId() + ": missing parent " + oboParentAccId);
                    missingParents++;
                }
            }
        }
    }

    void qcOmimPs(Collection<String> omimPsIds, Term termInRgd) throws Exception {

        // todo: determine synonyms that are no longer active
        // the current code only inserts missing OMIM:PS ids

        for( String omimPsId: omimPsIds ) {
            List<TermSynonym> syns = dao.getActiveSynonymsByName("RDO", omimPsId);
            if( syns.isEmpty() ) {
                TermSynonym tsyn = new TermSynonym();
                tsyn.setName(omimPsId);
                tsyn.setType("xref");
                tsyn.setCreatedDate(new Date());
                tsyn.setLastModifiedDate(new Date());
                tsyn.setTermAcc(termInRgd.getAccId());
                dao.insertTermSynonym(tsyn, "OBO");
                omimPsIdsInserted++;
            } else {
                // check if OMIM PS id is assigned to the same term in RGD as well as in DO
                if( syns.size()>1 ) {
                    omimPsIdsMultiple++;
                    System.out.println(omimPsId+" assigned to multiple terms: "+Utils.concatenate(", ", syns, "getTermAcc"));
                }
                else {
                    omimPsIdsUpToDate++;
                }
            }
        }
    }

    void qcSynonyms( OboTerm oboTerm, Term termInRgd ) throws Exception {
        // remove from incoming synonyms the synonyms identical to term name
        String termNameUniqueKey = getSynonymUniqueKey(termInRgd.getTerm());
        oboTerm.synonyms.remove(termNameUniqueKey);

        List<TermSynonym> synonymsInRgd = dao.getTermSynonyms(termInRgd.getAccId());

        Map<String, TermSynonym> synonymInRgdMap = new HashMap<>();
        for( TermSynonym tsyn: synonymsInRgd ) {
            String synUK = getSynonymUniqueKey(tsyn.getName());
            if( synUK.equals(termNameUniqueKey) ) {
                System.out.println(termInRgd.getAccId()+" synonym in RGD same as term name: "+tsyn.getName());
            }

            TermSynonym synDuplicate = synonymInRgdMap.put(synUK, tsyn);
            if( synDuplicate!=null ) {
                System.out.println(termInRgd.getAccId()+" duplicate synonym in RGD: "+tsyn.getName());
            }
        }

        deleteObsoleteIcdSynonyms(oboTerm.synonyms, synonymInRgdMap);

        oboTerm.synonyms.keySet().removeAll(synonymInRgdMap.keySet());

        if( !oboTerm.synonyms.isEmpty() ) {
            synQcFile.write(oboTerm.id+" ["+oboTerm.name+"]\n");
            for( TermSynonym tsyn: oboTerm.synonyms.values() ) {

                // is there a term with same name as synonym? if yes, suppress it
                Term term2 = dao.getRdoTermByTermName(tsyn.getName());
                if( term2!=null ) {
                    synQcFile.write("  SUPPRESSED " + tsyn.getName() + " [" + tsyn.getType() + "]\n");
                    suppressedSynonyms++;
                    continue;
                }

                // is such a synonym already present for a different term?
                List<Term> terms = dao.getRdoTermsBySynonym(tsyn.getName());
                if( terms.isEmpty() ) {
                    synQcFile.write("  INSERTED " + tsyn.getName() + " [" + tsyn.getType() + "]\n");
                    tsyn.setTermAcc(termInRgd.getAccId());
                    dao.insertTermSynonym(tsyn, "OBO");
                    insertedSynonyms++;
                } else {
                    synQcFile.write("  SUPPRESSED " + tsyn.getName() + " [" + tsyn.getType() + "]\n");
                    suppressedSynonyms++;
                }
            }
            synQcFile.write("\n");
        }
    }

    void deleteObsoleteIcdSynonyms(Map<String, TermSynonym> incomingSynonyms, Map<String, TermSynonym> synonymsInRgd) throws Exception {

        // find those 'ICD10CM:xxx' and 'ICD9CM:xxx' synonyms that are in RGD but they are not among incoming synonyms
        // they are obsolete and they must be deleted
        List<TermSynonym> obsoleteSynonymsInRgd = null;
        Iterator<Map.Entry<String,TermSynonym>> it = synonymsInRgd.entrySet().iterator();
        while( it.hasNext() ) {
            Map.Entry<String,TermSynonym> entry = it.next();
            String key = entry.getKey();
            if( key.endsWith(".icd10cm") || key.endsWith(".icd9cm") ) {
                if( !incomingSynonyms.containsKey(key) ) {
                    if( obsoleteSynonymsInRgd==null ) {
                        obsoleteSynonymsInRgd = new ArrayList<>();
                    }
                    obsoleteSynonymsInRgd.add(entry.getValue());
                    it.remove();
                }
            }
        }

        if( obsoleteSynonymsInRgd!=null ) {
            dao.deleteTermSynonyms(obsoleteSynonymsInRgd);
            deletedSynonyms += obsoleteSynonymsInRgd.size();
        }
    }

    static String getSynonymUniqueKey(String synonymName) {
        if( synonymName==null )
            return "";
        String processedName = synonymName.toLowerCase();
        String[] words = processedName.split("\\W");
        Arrays.sort(words);
        processedName = Utils.concatenate(words, ".");
        return processedName.intern();
    }

    static class OboTerm {
        public String id;
        public String name;
        public String def;
        public boolean isObsolete;
        public Set<String> parentAccIds = new TreeSet<>();
        public Set<String> omimPSIds = new TreeSet<>();
        public Map<String, TermSynonym> synonyms = new HashMap<>();
        public List<TermXRef> xrefs;

        public void parseSynonym(String line) {

            Integer[] startStopPos = new Integer[2];
            if( !parseQuotedString(startStopPos, line) ) {
                System.out.println("*** unexpected synonym: double quote parsing error");
                return;
            }

            TermSynonym tsyn = new TermSynonym();
            tsyn.setName(line.substring(startStopPos[0]+1, startStopPos[1]).replace("\\\"", "\"").trim());

            // pad the term name with spaces
            StringBuffer buf = new StringBuffer(line);
            buf.delete(startStopPos[0], startStopPos[1]+1);

            // find synonym type
            int pos1 = buf.indexOf(" EXACT ");
            if( pos1>=0 ) {
                tsyn.setType("exact_synonym");
                buf.delete(pos1, pos1+7);
            }
            if( pos1<0 ) {
                pos1 = buf.indexOf(" RELATED ");
                if (pos1 >= 0) {
                    tsyn.setType("related_synonym");
                    buf.delete(pos1, pos1+9);
                }
            }
            if( pos1<0 ) {
                pos1 = buf.indexOf(" NARROW ");
                if (pos1 >= 0) {
                    tsyn.setType("narrow_synonym");
                    buf.delete(pos1, pos1+8);
                }
            }
            if( pos1<0 ) {
                pos1 = buf.indexOf(" BROAD ");
                if (pos1 >= 0) {
                    tsyn.setType("broad_synonym");
                    buf.delete(pos1, pos1+7);
                }
            }
            if( pos1<0 ) {
                System.out.println("unknown type of synonym: "+line);
            }

            // parse synonym xrefs
            pos1 = buf.indexOf("[");
            int pos2 = buf.lastIndexOf("]");
            if( pos1>=0 && pos2>pos1 ) {
                if( pos2==1+pos1 ) {
                    // empty xref
                } else {
                    tsyn.setDbXrefs(buf.substring(pos1+1, pos2));
                }
            }

            String synUniqueKey = getSynonymUniqueKey(tsyn.getName());
            synonyms.put(synUniqueKey, tsyn);
        }

        void parseXrefSynonym(String synName) {
            TermSynonym tsyn = new TermSynonym();
            tsyn.setName(synName);
            tsyn.setType("xref");

            String synUniqueKey = getSynonymUniqueKey(tsyn.getName());
            synonyms.put(synUniqueKey, tsyn);
        }
    }
}
