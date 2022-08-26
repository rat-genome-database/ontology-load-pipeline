package edu.mcw.rgd.dataload.ontologies.test;

import edu.mcw.rgd.dao.impl.OntologyXDAO;
import edu.mcw.rgd.dataload.ontologies.OntologyDAO;
import edu.mcw.rgd.datamodel.ontologyx.Term;
import edu.mcw.rgd.datamodel.ontologyx.TermSynonym;
import edu.mcw.rgd.process.Utils;

import java.io.BufferedWriter;
import java.util.*;

public class BioCycMapper {

    static OntologyXDAO odao = new OntologyXDAO();
    static OntologyDAO dao = new OntologyDAO();

    public static void main(String[] args) throws Exception {

        System.out.println("START");

        String ontId = "PW";
        run(ontId);
    }

    static void run(String ontId) throws Exception {

        TermNameMatcherForPW matcher = populateTermNameMatcher(ontId);

        BufferedWriter out = Utils.openWriter("BioCyc_to_"+ontId+"_mappings.txt");
        out.write("BIOCYC ID\tEFO term\tmatch by\t"+ontId+" ID\t"+ontId+" term\n");

        final String TAB = "\t";
        Set<String> efoTermsWithoutMatches = new HashSet<>();
        int termNr = 0;

        /*
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
                    if( efoXref.startsWith("MEDDRA:") ||
                            efoXref.startsWith("MEDGEN:") ||
                            efoXref.startsWith("MONDO:") ||
                            efoXref.startsWith("ORDO:") ||
                            efoXref.startsWith("ORPHANET:") ||
                            efoXref.startsWith("RGD:") ||
                            efoXref.startsWith("SYMP:") ||
                            efoXref.startsWith("VT:") ||
                            efoXref.startsWith("HTTP")) {

                        continue; // not processing those
                    }
                    if( efoXref.startsWith("DOID:") && ontId.equals("RDO") ) {
                        Term doTerm = dao.getTerm(efoXref);
                        if( doTerm!=null ) {
                            matches.add(efoXref +TAB+ doTerm.getAccId() +TAB+ doTerm.getTerm());
                        } else {
                            matchByXref(efoXref, matches, ontId);
                        }
                    }
                    else if( efoXref.startsWith("NCIT:") ) {
                        // replace "NCIT:" with "NCI:"
                        String nciAcc = "NCI:"+efoXref.substring(5);
                        matchByXref(nciAcc, matches, ontId);
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
                        matchByXref(gardAcc, matches, ontId);
                    }
                    else if( efoXref.startsWith("MESH:") ) {
                        matchByXref(efoXref, matches, ontId);
                    }
                    else if( efoXref.startsWith("OMIM:") ) {
                        matchByXref(efoXref, matches, ontId);
                    }
                    else if( efoXref.startsWith("UMLS:") ) {
                        matchByXref(efoXref, matches, ontId);
                    }
                    else if( efoXref.startsWith("MP:") && ontId.equals("MP") ) {
                        Term doTerm = dao.getTerm(efoXref);
                        if( doTerm!=null ) {
                            matches.add(efoXref +TAB+ doTerm.getAccId() +TAB+ doTerm.getTerm());
                        } else {
                            matchByXref(efoXref, matches, ontId);
                        }
                    } else if( efoXref.startsWith("HP:") && ontId.equals("HP") ) {
                        Term doTerm = dao.getTerm(efoXref);
                        if( doTerm!=null ) {
                            matches.add(efoXref +TAB+ doTerm.getAccId() +TAB+ doTerm.getTerm());
                        } else {
                            matchByXref(efoXref, matches, ontId);
                        }
                    }

                    else {
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
        */

        // dump all terms without any matches
        out.write("\n\nEFO TERMS WITHOUT ANY MATCHES\n====================\n");
        for( String efoTermWithoutMatch: efoTermsWithoutMatches ) {
            out.write(efoTermWithoutMatch);
        }

        out.close();
    }

    static void matchByXref(String xrefAcc, List<String> matches, String ontId) throws Exception {
        List<Term> doTerms = dao.getTermsBySynonym(ontId, xrefAcc);
        for( Term doTerm: doTerms ) {
            matches.add(xrefAcc +"\t"+ doTerm.getAccId() +"\t"+ doTerm.getTerm());
        }
        if( doTerms.isEmpty() ) {
            System.out.println("no matches for xref: "+xrefAcc);
        }
    }

    static TermNameMatcherForPW populateTermNameMatcher(String ontId) throws Exception {

        System.out.println("initializing term name matcher ...");

        TermNameMatcherForPW matcher = new TermNameMatcherForPW();
        matcher.loadTerms(odao.getActiveTerms(ontId));

        matcher.loadSynonyms(dao.getActiveSynonymsByType(ontId, "exact_synonym"));
        matcher.loadSynonyms(dao.getActiveSynonymsByType(ontId, "broad_synonym"));
        matcher.loadSynonyms(dao.getActiveSynonymsByType(ontId, "narrow_synonym"));
        matcher.loadSynonyms(dao.getActiveSynonymsByType(ontId, "related_synonym"));

        System.out.println("term name matcher populated!");
        return matcher;
    }
}
