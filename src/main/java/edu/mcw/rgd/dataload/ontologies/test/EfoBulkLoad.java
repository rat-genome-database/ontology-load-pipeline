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
        BufferedReader in = Utils.openReader(fname);

        int linesWithIssues = 0;
        int xrefsAlreadyInRgd = 0;
        int xrefsInserted = 0;

        //EFO ID	DO ID	DO term
        //EFO:0000094	DOID:0080630	B-lymphoblastic leukemia/lymphoma
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
            String xrefAcc = cols[0].trim();
            String doAcc = cols[1].trim();
            String doTermName = cols[2].trim();
            if( doTermName.startsWith("\"") && doTermName.endsWith("\"") ) {
                doTermName = doTermName.substring(1, doTermName.length()-1).trim();
            }

            if( xrefAcc.equals("EFO:0005716")) {
                System.out.println("stop");
            }
            if( !xrefAcc.startsWith("EFO:") && !xrefAcc.startsWith("MONDO:") ) {
                System.out.println(lineNr+". not found EFO/MONDO acc in line "+line);
                linesWithIssues++;
                continue;
            }
            if( !doAcc.startsWith("DOID:") ) {
                System.out.println(lineNr+". not found DOID acc in line "+line);
                linesWithIssues++;
                continue;
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
                continue;
            }

            TermSynonym syn = new TermSynonym();
            syn.setType("xref");
            syn.setName(xrefAcc);
            syn.setTermAcc(doAcc);
            dao.insertTermSynonym(syn, "BULKLOAD");
            System.out.println(lineNr+". inserted "+doAcc+" "+xrefAcc);
            xrefsInserted++;
        }
        in.close();

        System.out.println("lines with issues: "+linesWithIssues);
        System.out.println("xrefs already in RGD: "+xrefsAlreadyInRgd);
        System.out.println("xrefs inserted: "+xrefsInserted);
    }
}
