package edu.mcw.rgd.dataload.ontologies.test;

import edu.mcw.rgd.dataload.ontologies.OntologyDAO;
import edu.mcw.rgd.datamodel.ontologyx.Term;
import edu.mcw.rgd.datamodel.ontologyx.TermSynonym;
import edu.mcw.rgd.process.Utils;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 */
public class SynonymLoaderVTCMO {

    public static void main(String[] args) throws Exception {

        boolean dryRun = false;
        final String SOURCE = "CMO_VT_XREFS";

        OntologyDAO dao = new OntologyDAO();
        System.out.println(dao.getConnectionInfo());

        String fname = "/Users/mtutaj/Downloads/CMO-VTO term pairs for bulk reciprocal XREF assignment.txt";
        List<String> lines = loadLines(fname);

        int linesWithIssues = 0;
        int synonymsInserted = 0;
        int synonymsUpToDate = 0;
        int pairsHandled = 0;

        //example
        //CMO ID: 	CMO_TERM	VT ID: assign as XREF to CMO term on same line (NOTE: Some CMO terms will get two VT IDs as XREFs.)	VT TERM
        //CMO:0000002	heart rate	VT:2000009	heart pumping trait

        for( String line: lines ) {
            System.out.println("LINE  "+line);
            String[] cols = line.split("[\\t]", -1);
            if( cols.length<4 ) {
                linesWithIssues++;
                continue;
            }
            String cmoTermName = getText(cols[1]);
            String cmoAcc = getText(cols[0]);
            String vtTermName = getText(cols[3]);
            String vtAccs = getText(cols[2]);
            String[] vtAccArray = vtAccs.split("[/]");

            if( !cmoAcc.startsWith("CMO:") || cmoAcc.length()!=11 ) {
                System.out.println("*** invalid CMO accession: "+cmoAcc+";   line: "+line);
                linesWithIssues++;
                continue;
            }

            Term cmoTerm = dao.getTerm(cmoAcc);
            if( cmoTerm==null ) {
                System.out.println("*** "+cmoAcc+" not found in RGD!");
                linesWithIssues++;
                continue;
            }

            for( String vtAcc: vtAccArray ) {
                if( !vtAcc.startsWith("VT:") || vtAcc.length()!=10 ) {
                    System.out.println("*** invalid VT accession: "+vtAcc+";   line: "+line);
                    linesWithIssues++;
                } else {

                    pairsHandled++;

                    Term vtTerm = dao.getTerm(vtAcc);
                    if( vtTerm==null ) {
                        System.out.println("*** "+vtAcc+" not found in RGD!");
                        linesWithIssues++;
                    } else {

                        List<TermSynonym> synonyms = dao.getTermSynonyms(cmoAcc);
                        boolean synFound = false;
                        for (TermSynonym tsyn : synonyms) {
                            if (tsyn.getType().equals("xref") && tsyn.getName().equals(vtAcc)) {
                                synonymsUpToDate++;
                                synFound = true;
                                break;
                            }
                        }
                        if( !synFound ) {
                            TermSynonym syn = new TermSynonym();
                            syn.setName(vtAcc);
                            syn.setType("xref");
                            syn.setTermAcc(cmoAcc);
                            dao.insertTermSynonym(syn, SOURCE);
                            synonymsInserted++;
                        }


                        synonyms = dao.getTermSynonyms(vtAcc);
                        synFound = false;
                        for (TermSynonym tsyn : synonyms) {
                            if (tsyn.getType().equals("xref") && tsyn.getName().equals(cmoAcc)) {
                                synonymsUpToDate++;
                                synFound = true;
                                break;
                            }
                        }
                        if( !synFound ) {
                            TermSynonym syn = new TermSynonym();
                            syn.setName(cmoAcc);
                            syn.setType("xref");
                            syn.setTermAcc(vtAcc);
                            dao.insertTermSynonym(syn, SOURCE);
                            synonymsInserted++;
                        }
                    }
                }
            }
        }
        System.out.println("***");
        System.out.println("pairs handled: "+pairsHandled);
        System.out.println("lines with issues: "+linesWithIssues);
        System.out.println("synonyms inserted: "+synonymsInserted);
        System.out.println("synonyms up-to-date: "+synonymsUpToDate);
    }

    static List<String> loadLines( String fname ) throws IOException {

        List<String> lines = new ArrayList<>();
        BufferedReader in = Utils.openReader(fname);

        int fixups = 0;
        String line;
        int lineNr = 0;
        while( (line=in.readLine())!=null ) {
            lineNr++;
            String[] cols = line.split("[\\t]", -1);
            if( cols.length<4 ) {
                System.out.println(lineNr+". ### line skipped: "+line);
                continue;
            }

            // fix ups for tab-separated content
            if( line.contains("\t\"\t") ) {
                line = line.replace("\t\"\t", "\"\t");
                fixups++;
            }
            lines.add(line);
        }
        in.close();

        System.out.println("line fix ups: "+fixups);
        Collections.shuffle(lines);
        return lines;
    }

    static String getText(String s) {
        String newData = s.replace("\u00A0", " ").trim(); // replace non breakable space

        if( newData.startsWith("\"") ) {
            newData = newData.substring(1).trim();
        }

        if( newData.endsWith("\"") ) {
            newData = newData.substring(0, newData.length()-1).trim();
        }

        return newData;
    }
}
