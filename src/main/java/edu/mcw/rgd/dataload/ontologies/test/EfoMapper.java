package edu.mcw.rgd.dataload.ontologies.test;

import edu.mcw.rgd.dao.impl.OntologyXDAO;
import edu.mcw.rgd.dataload.ontologies.OntologyDAO;
import edu.mcw.rgd.datamodel.ontologyx.Term;
import edu.mcw.rgd.datamodel.ontologyx.TermSynonym;
import edu.mcw.rgd.process.Utils;

import java.io.BufferedWriter;
import java.util.*;

public class EfoMapper {

    public static void main(String[] args) throws Exception {

        System.out.println("START");

        OntologyDAO dao = new OntologyDAO();

        TermNameMatcher matcher = populateTermNameMatcher(dao);

        BufferedWriter out = Utils.openWriter("efo_do_mappings.txt");
        out.write("EFO ID\tEFO term\tmatch by\tDO ID\tDO term\n");

        final String TAB = "\t";
        Set<String> efoTermsWithoutMatches = new HashSet<>();
        int termNr = 0;

        List<String> efoIds = dao.getAllTermAccIds("EFO");
        Collections.shuffle(efoIds);
        for( String efoId: efoIds ) {
            termNr++;
            System.out.println(termNr+" / "+efoIds.size());

            Term efoTerm = dao.getTerm(efoId);
            List<TermSynonym> synonyms = dao.getTermSynonyms(efoId);

            List<String> nonXrefSynonyms = new ArrayList<>();
            List<String> matches = new ArrayList<>();

            for( TermSynonym tsyn: synonyms ) {

                // ignore relations to external ontologies, like CHEBI, MONDO etc
                if( tsyn.getType().equals("external_ontology") ||
                        tsyn.getType().equals("cyclic_relationship") ||
                        tsyn.getType().equals("replaced_by") ||
                        tsyn.getType().equals("axiom_lost") ||
                        tsyn.getType().equals("see_also")) {
                    continue; // not processing those
                }

                if( tsyn.getType().equals("xref") || tsyn.getType().equals("alt_id") ) {
                    String efoXref = tsyn.getName().toUpperCase();
                    if( efoXref.startsWith("AAO:") ||
                        efoXref.startsWith("ATCC:") ||
                        efoXref.startsWith("ATC_CODE:") ||
                            efoXref.startsWith("BAO:") ||
                        efoXref.startsWith("BILADO:") ||
                        efoXref.startsWith("BIRN_ANAT:") ||
                        efoXref.startsWith("BTO:") ||
                        efoXref.startsWith("CASRN:") ||
                        efoXref.startsWith("CHMO:") ||
                        efoXref.startsWith("CL:") ||
                        efoXref.startsWith("CLO:") ||
                        efoXref.startsWith("CMO:") ||
                        efoXref.startsWith("COHD:") ||
                            efoXref.startsWith("CRISP") ||
                        efoXref.startsWith("CSP:") ||
                        efoXref.startsWith("DERMO:") ||
                        efoXref.startsWith("DI:") ||
                        efoXref.startsWith("DOI:") ||
                        efoXref.startsWith("DSSTOX_GENERIC_SID:") ||
                        efoXref.startsWith("EFO:") ||
                        efoXref.startsWith("EHDAA:") ||
                        efoXref.startsWith("EMAPA:") ||
                            efoXref.startsWith("ENM:") ||
                        efoXref.startsWith("ERO:") ||
                        efoXref.startsWith("EV:") ||
                        efoXref.startsWith("EVM:") ||
                        efoXref.startsWith("FBBT:") ||
                        efoXref.startsWith("FBDV:") ||
                        efoXref.startsWith("FBTC:") ||
                        efoXref.startsWith("FMA:") ||
                        efoXref.startsWith("GERMPLASM:") ||
                        efoXref.startsWith("GO:") ||
                        efoXref.startsWith("GTR:") ||
                        efoXref.startsWith("HGNC:") ||
                        efoXref.startsWith("HP:") ||
                            efoXref.startsWith("HSAPDV:") ||
                        efoXref.startsWith("ICD10:") ||
                        efoXref.startsWith("ICD10CM:") ||
                        efoXref.startsWith("ICD10EXP:") ||
                        efoXref.startsWith("ICD10WHO:") ||
                        efoXref.startsWith("ICD9:") ||
                        efoXref.startsWith("ICD9CM:") ||
                        efoXref.startsWith("ICDO:") ||
                        efoXref.startsWith("IDOMAL:") ||
                            efoXref.startsWith("ISBN:") ||
                        efoXref.startsWith("JAX:") ||
                        efoXref.startsWith("KEGG:") ||
                        efoXref.startsWith("MA:") ||
                        efoXref.startsWith("MAP:") ||
                        efoXref.startsWith("MAT:") ||
                        efoXref.startsWith("MCC:") ||
                        efoXref.startsWith("MEDDRA:") ||
                        efoXref.startsWith("MEDGEN:") ||
                        efoXref.startsWith("MFO:") ||
                        efoXref.startsWith("MMUSDV:") ||
                        efoXref.startsWith("MO:") ||
                        efoXref.startsWith("MONDO:") ||
                        efoXref.startsWith("MP:") ||
                            efoXref.startsWith("MTH:") ||
                        efoXref.startsWith("NCIM:") ||
                        efoXref.startsWith("NCI METATHESAURUS:") ||
                        efoXref.startsWith("NIFSTD:") ||
                        efoXref.startsWith("NPO:") ||
                        efoXref.startsWith("OBI:") ||
                        efoXref.startsWith("OBO:") ||
                        efoXref.startsWith("OMIMPS:") ||
                        efoXref.startsWith("OMIT:") ||
                        efoXref.startsWith("ONCOTREE:") ||
                        efoXref.startsWith("ORCID:") ||
                        efoXref.startsWith("ORDO:") ||
                        efoXref.startsWith("ORPHANET:") ||
                        efoXref.startsWith("PATO:") ||
                        efoXref.startsWith("PERSON:") ||
                        efoXref.startsWith("PMID:") ||
                        efoXref.startsWith("PO:") ||
                        efoXref.startsWith("PR:") ||
                            efoXref.startsWith("REACTOME:") ||
                        efoXref.startsWith("RGD:") ||
                        efoXref.startsWith("SAEL:") ||
                        efoXref.startsWith("SCTID:") ||
                        efoXref.startsWith("SNOMEDCT:") ||
                            efoXref.startsWith("SYMP:") ||
                        efoXref.startsWith("TADS:") ||
                        efoXref.startsWith("TAO:") ||
                        efoXref.startsWith("TGMA:") ||
                        efoXref.startsWith("UMLS:") ||
                        efoXref.startsWith("UNIPROT:") ||
                            efoXref.startsWith("VT:") ||
                        efoXref.startsWith("WBBT:") ||
                        efoXref.startsWith("WBLS:") ||
                        efoXref.startsWith("WIKIPEDIA:") ||
                        efoXref.startsWith("XAO:") ||
                        efoXref.startsWith("ZEA:") ||
                        efoXref.startsWith("ZFA:") ||
                        efoXref.startsWith("ZFS:") ||
                        efoXref.startsWith("HTTP")) {

                        continue; // not processing those
                    }
                    if( efoXref.startsWith("DOID:") ) {
                        Term doTerm = dao.getTerm(efoXref);
                        if( doTerm!=null ) {
                            matches.add(efoXref +TAB+ doTerm.getAccId() +TAB+ doTerm.getTerm());
                        } else {
                            matchByXref(efoXref, matches, dao);
                        }
                    }
                    else if( efoXref.startsWith("NCIT:") ) {
                        // replace "NCIT:" with "NCI:"
                        String nciAcc = "NCI:"+efoXref.substring(5);
                        matchByXref(nciAcc, matches, dao);
                    }
                    else if( efoXref.startsWith("GARD:") ) {
                        // remove leading zeros f.e. 'GARD:0000123' must be 'GARD:123'
                        String gardAcc = "GARD:";
                        int i;
                        for (i = 5; i < efoXref.length(); i++) {
                            if (efoXref.charAt(i) != '0') {
                                break;
                            }
                        }
                        gardAcc += efoXref.substring(i);
                        matchByXref(gardAcc, matches, dao);
                    }
                    else if( efoXref.startsWith("MESH:") ) {
                        matchByXref(efoXref, matches, dao);
                    }
                    else if( efoXref.startsWith("OMIM:") ) {
                            matchByXref(efoXref, matches, dao);
                    } else {
                        //throw new Exception("unexpected xref type: " + efoXref);
                    }
                    continue;
                }

                if(tsyn.getType().equals("exact_synonym") ||
                        tsyn.getType().equals("related_synonym") ||
                        tsyn.getType().equals("narrow_synonym") ||
                        tsyn.getType().equals("broad_synonym")) {

                    nonXrefSynonyms.add(tsyn.getName().toUpperCase());
                } else {
                    throw new Exception("unknown synonym type");
                }
            }

            if( !matches.isEmpty() ) {
                // dump all matches
                out.write("\n");
                for( String m: matches ) {
                    out.write(efoId +TAB+ efoTerm.getTerm() +TAB+ m + "\n");
                }
            } else { // matches by xrefs are empty
                // try to match by term name
                Set<String> doAccIds = matcher.getMatches(efoTerm.getTerm());
                if( doAccIds!=null && !doAccIds.isEmpty() ) {
                    out.write("\n");
                    for( String doAcc: doAccIds ) {
                        Term t = dao.getTerm(doAcc);
                        String termName = t==null ? "???" : t.getTerm();
                        out.write(efoId +TAB+ efoTerm.getTerm() +TAB+ efoTerm.getTerm() +TAB+ doAcc +TAB+ termName +"\n");
                    }
                } else if( nonXrefSynonyms.isEmpty() ) {
                    efoTermsWithoutMatches.add(efoId +TAB+ efoTerm.getTerm() +"\n");
                } else {
                    // no match by term name - try synonyms
                    doAccIds = new HashSet<>();
                    for( String nonXrefSynonym: nonXrefSynonyms ) {
                        Set<String> matchesBySynonym = matcher.getMatches(nonXrefSynonym);
                        if( matchesBySynonym!=null ) {
                            doAccIds.addAll(matchesBySynonym);
                        }
                    }
                    if( doAccIds!=null && !doAccIds.isEmpty() ) {
                        out.write("\n");
                        for( String doAcc: doAccIds ) {
                            Term t = dao.getTerm(doAcc);
                            String termName = t==null ? "???" : t.getTerm();
                            out.write(efoId +TAB+ efoTerm.getTerm() +TAB+ efoTerm.getTerm() +TAB+ doAcc +TAB+ termName +"\n");
                        }
                    } else {
                        efoTermsWithoutMatches.add(efoId +TAB+ efoTerm.getTerm() +"\n");
                    }
                }
            }
        }

        // dump all terms without any matches
        out.write("\n\nEFO TERMS WITHOUT ANY MATCHES\n====================\n");
        for( String efoTermWithoutMatch: efoTermsWithoutMatches ) {
            out.write(efoTermWithoutMatch);
        }

        out.close();
    }

    static void matchByXref(String xrefAcc, List<String> matches, OntologyDAO dao) throws Exception {
        List<Term> doTerms = dao.getRdoTermsBySynonym(xrefAcc);
        for( Term doTerm: doTerms ) {
            matches.add(xrefAcc +"\t"+ doTerm.getAccId() +"\t"+ doTerm.getTerm());
        }
        if( doTerms.isEmpty() ) {
            System.out.println("no matches for xref: "+xrefAcc);
        }
    }

    static TermNameMatcher populateTermNameMatcher(OntologyDAO dao) throws Exception {

        System.out.println("initializing term name matcher ...");

        TermNameMatcher matcher = new TermNameMatcher();
        matcher.loadSynonyms(dao.getActiveSynonymsByType("RDO", "exact_synonym"));
        matcher.loadSynonyms(dao.getActiveSynonymsByType("RDO", "broad_synonym"));
        matcher.loadSynonyms(dao.getActiveSynonymsByType("RDO", "narrow_synonym"));
        matcher.loadSynonyms(dao.getActiveSynonymsByType("RDO", "related_synonym"));

        OntologyXDAO odao = new OntologyXDAO();
        matcher.loadTerms(odao.getActiveTerms("RDO"));

        System.out.println("term name matcher populated!");
        return matcher;
    }
}
