package edu.mcw.rgd.dataload.ontologies.test;

import edu.mcw.rgd.dataload.ontologies.OntologyDAO;
import edu.mcw.rgd.datamodel.ontologyx.Term;
import edu.mcw.rgd.datamodel.ontologyx.TermSynonym;
import edu.mcw.rgd.process.Utils;

import java.io.BufferedReader;
import java.util.List;

/**
 * Created by mtutaj on 02/26/2024.
 */
public class SynonymLoader {

    public static void main(String[] args) throws Exception {

        boolean dryRun = false;

        OntologyDAO dao = new OntologyDAO();
        System.out.println(dao.getConnectionInfo());

        String fname = "/tmp/w/clinvar-rdo.txt";
        BufferedReader in = Utils.openReader(fname);

        int linesWithIssues = 0;
        int synonymsInserted = 0;
        int synonymsUpToDate = 0;

        //example
        // Term	| Synonym type | RDO term | RDO Term ID
        // ATR-RELATED CONDITION	broad_synonym	"Cutaneous Telangiectasia and Cancer Syndrome, Familial"	DOID:9002856
        String line;
        int lineNr = 0;
        while( (line=in.readLine())!=null ) {
            lineNr++;
            String[] cols = line.split("[\\t]", -1);
            if( cols.length<4 ) {
                System.out.println(lineNr+". ### line skipped: "+line);
                linesWithIssues++;
                continue;
            }
            String synonymName = getText(cols[0]);
            String synonymType = getText(cols[1]);
            String termName = getText(cols[2]);
            String termAcc = getText(cols[3]);

            if( !termAcc.startsWith("DOID:")) {
                System.out.println(lineNr+". ### not found DOID acc in line "+line);
                linesWithIssues++;
                continue;
            }

            Term term = dao.getTerm(termAcc);
            if( term==null ) {
                System.out.println(lineNr+". ### "+termAcc+" not found in RGD!");
                linesWithIssues++;
                continue;
            }
            boolean lineWithProblem = false;
            while( term.isObsolete() ) {

                List<TermSynonym> synonyms = dao.getTermSynonyms(termAcc);
                String replacedBy = null;
                for( TermSynonym s: synonyms ) {
                    if( s.getType().equals("replaced_by") ) {
                        replacedBy = s.getName().trim().toUpperCase();
                        break;
                    }
                }
                if( replacedBy==null ) {
                    System.out.println(lineNr + ". ### " + termAcc + " is obsolete in RGD!");
                    lineWithProblem = true;
                    break;
                }

                System.out.println(lineNr+". ### "+termAcc+" has been replaced by "+replacedBy);
                termAcc = replacedBy;

                term = dao.getTerm(termAcc);
                if( term==null ) {
                    System.out.println(lineNr+". ### "+termAcc+" not found in RGD!");
                    lineWithProblem = true;
                    break;
                }
            }
            if( lineWithProblem ) {
                linesWithIssues++;
                continue;
            }

            if( synonymName!=null && synonymType!=null ) {

                List<TermSynonym> synonyms = dao.getTermSynonyms(termAcc);
                boolean synonymAlreadyInRgd = false;
                for( TermSynonym s: synonyms ) {
                    if( s.getName().equalsIgnoreCase(synonymName) ) {
                        System.out.println(lineNr+". "+termAcc+" ["+synonymName+"] already in RGD!");
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
                    syn.setTermAcc(termAcc);
                    if( !dryRun ) {
                        dao.insertTermSynonym(syn, "BULKLOAD");
                    }
                    System.out.println(lineNr + ". inserted " + termAcc + " [" + synonymName + "] (" + synonymType + ")");
                    synonymsInserted++;
                }
            }
        }
        in.close();

        System.out.println("lines with issues: "+linesWithIssues);
        System.out.println("synonyms inserted: "+synonymsInserted);
        System.out.println("synonyms up-to-date: "+synonymsUpToDate);
    }

    static String getText(String s) {
        String newData = s.replace("\u00A0", " ").trim(); // replace non breakable space

        if( newData.startsWith("\"") && newData.endsWith("\"") ) {
            newData = getText(newData.substring(1, newData.length()-1));
        }

        return newData;
    }
}
