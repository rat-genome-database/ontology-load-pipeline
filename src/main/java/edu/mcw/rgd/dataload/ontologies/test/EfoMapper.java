package edu.mcw.rgd.dataload.ontologies.test;

import edu.mcw.rgd.dao.impl.OntologyXDAO;
import edu.mcw.rgd.dataload.ontologies.OntologyDAO;
import edu.mcw.rgd.datamodel.ontologyx.Term;
import edu.mcw.rgd.datamodel.ontologyx.TermSynonym;
import edu.mcw.rgd.process.Utils;

import java.io.BufferedWriter;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public class EfoMapper {

    static OntologyXDAO odao = new OntologyXDAO();
    static OntologyDAO dao = new OntologyDAO();

    public static void main(String[] args) throws Exception {

        System.out.println("START");

        // if true, run the matcher only for those EFO terms that are present in GWAS data in RGD
        boolean limitEfoToGWAS = true;

        String ontId;

        /*
        ontId = "MP";
        run(ontId, limitEfoToGWAS);

        ontId = "HP";
        run(ontId, limitEfoToGWAS);
        */

        ontId = "CMO";
        run(ontId, limitEfoToGWAS);

        /*
        ontId = "RDO";
        run(ontId);
        */
    }

    static void run(String ontId, boolean limitEfoToGWAS) throws Exception {

        TermNameMatcher matcher = populateTermNameMatcher(ontId);

        BufferedWriter out = Utils.openWriter("EFO_to_"+ontId+"_mappings.txt");
        out.write("EFO ID\tEFO term\tmatch by\t"+ontId+" ID\t"+ontId+" term\n");

        final String TAB = "\t";
        AtomicInteger termNr = new AtomicInteger(0);

        List<String> efoIds1 = dao.getAllTermAccIds("EFO");

        if( limitEfoToGWAS ) {
            boolean efoSinglets = false;
            if( ontId.equals("CMO") ) {
                efoSinglets = true;
            }

            Collection<String> gwasEfoIds = dao.getEfoIdsFromGWAS(efoSinglets);

            List<String> efoIdsNotInRgd = new ArrayList<>(gwasEfoIds);
            efoIdsNotInRgd.removeAll(efoIds1);
            System.out.println("efoIdsNotInRgd="+efoIdsNotInRgd.size());

            efoIds1 = new ArrayList<>(gwasEfoIds);
        }
        final List<String> efoIds = new ArrayList<>(efoIds1);
        Collections.shuffle(efoIds);

        Map<String, String> resultMap = new TreeMap<>();
        Set<String> efoTermsWithoutMatches = new TreeSet<>();


        efoIds.parallelStream().forEach( efoId -> {

            int nr = termNr.getAndIncrement();
            System.out.println(nr+" / "+efoIds.size());

            try {
                Term efoTerm = dao.getTerm(efoId);
                List<TermSynonym> synonyms = dao.getTermSynonyms(efoId);

                List<String> nonXrefSynonyms = new ArrayList<>();
                List<String> matches = new ArrayList<>();

                for (TermSynonym tsyn : synonyms) {

                    // ignore relations to external ontologies, like CHEBI, MONDO etc
                    if (tsyn.getType().equals("external_ontology") ||
                            tsyn.getType().equals("cyclic_relationship") ||
                            tsyn.getType().equals("replaced_by") ||
                            tsyn.getType().equals("axiom_lost") ||
                            tsyn.getType().equals("see_also")) {
                        continue; // not processing those
                    }

                    if (tsyn.getType().equals("xref") || tsyn.getType().equals("alt_id")) {
                        String efoXref = tsyn.getName().toUpperCase();
                        if (efoXref.startsWith("MEDDRA:") ||
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
                        if (efoXref.startsWith("DOID:") && ontId.equals("RDO")) {
                            Term doTerm = dao.getTerm(efoXref);
                            if (doTerm != null) {
                                matches.add(efoXref + TAB + doTerm.getAccId() + TAB + doTerm.getTerm());
                            } else {
                                matchByXref(efoXref, matches, ontId);
                            }
                        } else if (efoXref.startsWith("NCIT:")) {
                            // replace "NCIT:" with "NCI:"
                            String nciAcc = "NCI:" + efoXref.substring(5);
                            matchByXref(nciAcc, matches, ontId);
                        } else if (efoXref.startsWith("GARD:")) {
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
                        } else if (efoXref.startsWith("MESH:")) {
                            matchByXref(efoXref, matches, ontId);
                        } else if (efoXref.startsWith("OMIM:")) {
                            matchByXref(efoXref, matches, ontId);
                        } else if (efoXref.startsWith("UMLS:")) {
                            matchByXref(efoXref, matches, ontId);
                        } else if (efoXref.startsWith("MP:") && ontId.equals("MP")) {
                            Term doTerm = dao.getTerm(efoXref);
                            if (doTerm != null) {
                                matches.add(efoXref + TAB + doTerm.getAccId() + TAB + doTerm.getTerm());
                            } else {
                                matchByXref(efoXref, matches, ontId);
                            }
                        } else if (efoXref.startsWith("HP:") && ontId.equals("HP")) {
                            Term doTerm = dao.getTerm(efoXref);
                            if (doTerm != null) {
                                matches.add(efoXref + TAB + doTerm.getAccId() + TAB + doTerm.getTerm());
                            } else {
                                matchByXref(efoXref, matches, ontId);
                            }
                        } else {
                            //throw new Exception("unexpected xref type: " + efoXref);
                        }
                        continue;
                    }

                    if (tsyn.getType().equals("exact_synonym") ||
                            tsyn.getType().equals("related_synonym") ||
                            tsyn.getType().equals("narrow_synonym") ||
                            tsyn.getType().equals("broad_synonym")) {

                        nonXrefSynonyms.add(tsyn.getName().toUpperCase());
                    } else {
                        throw new Exception("unknown synonym type");
                    }
                }

                StringBuffer buf = new StringBuffer();

                if (!matches.isEmpty()) {
                    // dump all matches
                    buf.append("\n");
                    for (String m : matches) {
                        buf.append(efoId + TAB + efoTerm.getTerm() + TAB + m + "\n");
                    }
                } else { // matches by xrefs are empty
                    // try to match by term name
                    Set<String> doAccIds = matcher.getMatches(efoTerm.getTerm());
                    if (doAccIds != null && !doAccIds.isEmpty()) {
                        buf.append("\n");
                        for (String doAcc : doAccIds) {
                            Term t = dao.getTerm(doAcc);
                            String termName = t == null ? "???" : t.getTerm();
                            buf.append(efoId + TAB + efoTerm.getTerm() + TAB + efoTerm.getTerm() + TAB + doAcc + TAB + termName + "\n");
                        }
                    } else if (nonXrefSynonyms.isEmpty()) {
                        synchronized(efoTermsWithoutMatches) {
                            efoTermsWithoutMatches.add(efoId + TAB + efoTerm.getTerm() + "\n");
                        }
                    } else {
                        // no match by term name - try synonyms
                        doAccIds = new HashSet<>();
                        for (String nonXrefSynonym : nonXrefSynonyms) {
                            Set<String> matchesBySynonym = matcher.getMatches(nonXrefSynonym);
                            if (matchesBySynonym != null) {
                                doAccIds.addAll(matchesBySynonym);
                            }
                        }
                        if (doAccIds != null && !doAccIds.isEmpty()) {
                            buf.append("\n");
                            for (String doAcc : doAccIds) {
                                Term t = dao.getTerm(doAcc);
                                String termName = t == null ? "???" : t.getTerm();
                                buf.append(efoId + TAB + efoTerm.getTerm() + TAB + efoTerm.getTerm() + TAB + doAcc + TAB + termName + "\n");
                            }
                        } else {
                            synchronized (efoTermsWithoutMatches) {
                                efoTermsWithoutMatches.add(efoId + TAB + efoTerm.getTerm() + "\n");
                            }
                        }
                    }
                }

                if( buf.length()>0 ) {
                    String str = buf.toString();
                    synchronized (resultMap) {
                        resultMap.put(efoId, str);
                    }
                }
            } catch( Exception e ) {
                throw new RuntimeException(e);
            }
        });

        // dump all terms without any matches
        for( String s: resultMap.values() ) {
            out.write(s);
        }

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

    static TermNameMatcher populateTermNameMatcher(String ontId) throws Exception {

        System.out.println("initializing term name matcher ...");

        TermNameMatcher matcher = new TermNameMatcher();
        matcher.loadSynonyms(dao.getActiveSynonymsByType(ontId, "exact_synonym"));
        matcher.loadSynonyms(dao.getActiveSynonymsByType(ontId, "broad_synonym"));
        matcher.loadSynonyms(dao.getActiveSynonymsByType(ontId, "narrow_synonym"));
        matcher.loadSynonyms(dao.getActiveSynonymsByType(ontId, "related_synonym"));

        matcher.loadTerms(odao.getActiveTerms(ontId));

        System.out.println("term name matcher populated!");
        return matcher;
    }
}
