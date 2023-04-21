package edu.mcw.rgd.dataload.ontologies.test;

import edu.mcw.rgd.dataload.ontologies.OntologyDAO;
import edu.mcw.rgd.datamodel.ontologyx.Term;
import edu.mcw.rgd.datamodel.ontologyx.TermSynonym;
import edu.mcw.rgd.process.Utils;

import java.io.BufferedReader;
import java.util.List;

public class EfoBulkLoad {

    public static void main(String[] args) throws Exception {

        OntologyDAO dao = new OntologyDAO();
        System.out.println(dao.getConnectionInfo());

        String fname = "/Users/mtutaj/Documents/efo_bulk_load.txt";
        fname = "/Users/mtutaj/Documents/EFO-RDO-bulk-load.txt";
        fname = "/tmp/EFO to HP-MP automapping bulk load.txt";
        BufferedReader in = Utils.openReader(fname);

        int linesWithIssues = 0;
        int xrefsAlreadyInRgd = 0;
        int xrefsInserted = 0;
        int synonymsInserted = 0;
        int synonymsUpToDate = 0;

        //EFO:0004720	MP:0002654	prion disease	broad_synonym
        String line;
        int lineNr = 0;
        while( (line=in.readLine())!=null ) {
            lineNr++;
            String[] cols = line.split("[\\t]", -1);
            if( cols.length<=3 ) {
                System.out.println(lineNr+". line skipped: "+line);
                linesWithIssues++;
                continue;
            }
            String xrefAcc = getText(cols[0]);
            String hpMpAcc = getText(cols[1]);
            String synonymName = getText(cols[2]);
            if( synonymName.startsWith("\"") && synonymName.endsWith("\"") ) {
                synonymName = getText(synonymName.substring(1, synonymName.length()-1));
            }
            String synonymType = getText(cols[3]);

            if( !xrefAcc.startsWith("EFO:") && !xrefAcc.startsWith("MONDO:") ) {
                System.out.println(lineNr+". not found EFO/MONDO acc in line "+line);
                linesWithIssues++;
                continue;
            }
            if( !hpMpAcc.startsWith("HP:") && !hpMpAcc.startsWith("MP:")) {
                System.out.println(lineNr+". not found HP/MP acc in line "+line);
                linesWithIssues++;
                continue;
            }


            Term term = dao.getTerm(hpMpAcc);
            if( term==null ) {
                System.out.println(lineNr+". "+hpMpAcc+" not found in RGD!");
                linesWithIssues++;
                continue;
            }

            boolean xrefAlreadyInRgd = false;
            List<TermSynonym> synonyms = dao.getTermSynonyms(hpMpAcc);
            for( TermSynonym syn: synonyms ) {
                if( syn.getName().equals(xrefAcc) ) {
                    System.out.println(lineNr+". "+hpMpAcc+" "+xrefAcc+" already in RGD!");
                    xrefAlreadyInRgd = true;
                    break;
                }
            }
            if( xrefAlreadyInRgd ) {
                xrefsAlreadyInRgd++;
            } else {
                TermSynonym syn = new TermSynonym();
                syn.setType("xref");
                syn.setName(xrefAcc);
                syn.setTermAcc(hpMpAcc);
                dao.insertTermSynonym(syn, "BULKLOAD");
                System.out.println(lineNr + ". inserted " + hpMpAcc + " " + xrefAcc);
                xrefsInserted++;
            }

            // process optional synonym
            if( synonymName!=null && synonymType!=null ) {
                boolean synonymAlreadyInRgd = false;
                for( TermSynonym s: synonyms ) {
                    if( s.getName().equalsIgnoreCase(synonymName) ) {
                        System.out.println(lineNr+". "+hpMpAcc+" ["+synonymName+"] already in RGD!");
                        synonymAlreadyInRgd = true;
                        break;
                    }
                }

                if( synonymAlreadyInRgd ) {
                    synonymsUpToDate++;
                } else if( !Utils.isStringEmpty(synonymName)) {
                    TermSynonym syn = new TermSynonym();
                    syn.setType(synonymType);
                    syn.setName(synonymName);
                    syn.setTermAcc(hpMpAcc);
                    dao.insertTermSynonym(syn, "BULKLOAD");
                    System.out.println(lineNr + ". inserted " + hpMpAcc + " [" + synonymName + "] (" + synonymType + ")");
                    synonymsInserted++;
                }
            }
        }
        in.close();

        System.out.println("lines with issues: "+linesWithIssues);
        System.out.println("xrefs already in RGD: "+xrefsAlreadyInRgd);
        System.out.println("xrefs inserted: "+xrefsInserted);
        System.out.println("synonyms inserted: "+synonymsInserted);
        System.out.println("synonyms up-to-date: "+synonymsUpToDate);
    }

    static String getText(String s) {
        String newData = s.replace("\u00A0", " ").trim(); // replace non breakable space
        return newData;
    }
}
