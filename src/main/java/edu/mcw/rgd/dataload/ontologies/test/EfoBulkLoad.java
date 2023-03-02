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
        BufferedReader in = Utils.openReader(fname);

        int linesWithIssues = 0;
        int xrefsAlreadyInRgd = 0;
        int xrefsInserted = 0;
        int synonymsInserted = 0;
        int synonymsUpToDate = 0;

        //EFO ID	DO ID	DO term synonym(optional) synonym_type(optional)
        //EFO:0000094	DOID:0080630	B-lymphoblastic leukemia/lymphoma
        //EFO:1000226	DOID:9004831	Colitis-Associated Neoplasms	Dysplasia in Ulcerative Colitis	related
        String line;
        int lineNr = 0;
        while( (line=in.readLine())!=null ) {
            lineNr++;
            String[] cols = line.split("[\\t]", -1);
            if( cols.length<3 ) {
                System.out.println(lineNr+". line skipped: "+line);
                linesWithIssues++;
                continue;
            }
            String xrefAcc = getText(cols[0]);
            String doAcc = getText(cols[1]);
            String doTermName = getText(cols[2]);
            if( doTermName.startsWith("\"") && doTermName.endsWith("\"") ) {
                doTermName = getText(doTermName.substring(1, doTermName.length()-1));
            }

            if( !xrefAcc.startsWith("EFO:") && !xrefAcc.startsWith("MONDO:") ) {
                System.out.println(lineNr+". not found EFO/MONDO acc in line "+line);
                linesWithIssues++;
                continue;
            }
            if( !doAcc.startsWith("DOID:") && !doAcc.startsWith("MP:")) {
                System.out.println(lineNr+". not found DOID/MP acc in line "+line);
                linesWithIssues++;
                continue;
            }

            // optional synonym name and type
            String synonymName = null;
            String synonymType = null;
            if( cols.length>=5 ) {
                synonymName = getText(cols[3]);
                synonymType = getText(cols[4]);
            }

            Term term = dao.getTerm(doAcc);
            if( term==null ) {
                System.out.println(lineNr+". "+doAcc+" not found in RGD!");
                linesWithIssues++;
                continue;
            }
            if( !term.getTerm().equalsIgnoreCase(doTermName) ) {
                System.out.println(lineNr+". do name mismatch: "+doAcc+" ["+term.getTerm()+"], incoming ["+doTermName+"]");
            }

            boolean xrefAlreadyInRgd = false;
            List<TermSynonym> synonyms = dao.getTermSynonyms(doAcc);
            for( TermSynonym syn: synonyms ) {
                if( syn.getName().equals(xrefAcc) ) {
                    System.out.println(lineNr+". "+doAcc+" "+xrefAcc+" already in RGD!");
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
                syn.setTermAcc(doAcc);
                dao.insertTermSynonym(syn, "BULKLOAD");
                System.out.println(lineNr + ". inserted " + doAcc + " " + xrefAcc);
                xrefsInserted++;
            }

            // process optional synonym
            if( synonymName!=null && synonymType!=null ) {
                boolean synonymAlreadyInRgd = false;
                for( TermSynonym s: synonyms ) {
                    if( s.getName().equalsIgnoreCase(synonymName) ) {
                        System.out.println(lineNr+". "+doAcc+" ["+synonymName+"] already in RGD!");
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
                    syn.setTermAcc(doAcc);
                    dao.insertTermSynonym(syn, "BULKLOAD");
                    System.out.println(lineNr + ". inserted " + doAcc + " [" + synonymName + "] (" + synonymType + ")");
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
