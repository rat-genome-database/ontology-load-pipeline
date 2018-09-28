package edu.mcw.rgd.dataload.ontologies.test;

import edu.mcw.rgd.dao.impl.OntologyXDAO;
import edu.mcw.rgd.datamodel.ontologyx.TermSynonym;
import edu.mcw.rgd.datamodel.ontologyx.TermXRef;
import edu.mcw.rgd.process.FileDownloader;
import edu.mcw.rgd.process.Utils;

import java.io.BufferedReader;
import java.util.Date;
import java.util.Iterator;
import java.util.List;

/**
 * Created by mtutaj on 5/30/2018.
 */
public class FixXrefsForPhenominerOntologies {

    public static void main(String[] args) throws Exception {

        // load the ontology file
        String oboFile = "ftp://ftp.rgd.mcw.edu/pub/ontology/experimental_condition/experimental_condition.obo";
        String ontId = "XCO";

        FileDownloader fd = new FileDownloader();
        fd.setExternalFile(oboFile);
        fd.setLocalFile("/tmp/xco.obo");
        String localFile = fd.downloadNew();

        OntologyXDAO odao = new OntologyXDAO();
        int xrefsDeleted = 0;
        int synonymInsertedCount = 0;
        int synonymInRgdCount = 0;

        String termAcc = null;
        List<TermSynonym> synonymsInRgd = null; // xref synonyms in rgd
        List<TermXRef> xrefsInRgd = null;
        String line;
        BufferedReader in = Utils.openReader(localFile);
        while( (line=in.readLine())!=null ) {
            if( Utils.isStringEmpty(line) ) {
                continue;
            }
            if( line.startsWith("id: ") ) {
                termAcc = line.substring(3).trim();

                synonymsInRgd = odao.getTermSynonyms(termAcc);
                Iterator<TermSynonym> it = synonymsInRgd.iterator();
                while( it.hasNext() ) {
                    TermSynonym syn = it.next();
                    if( !syn.getType().equals("xref") ) {
                        it.remove();
                    }
                }

                xrefsInRgd = odao.getTermXRefs(termAcc);
                continue;
            }

            if( line.startsWith("xref: ") ) {
                if( !termAcc.startsWith(ontId) ) {
                    continue;
                }
                String xref = line.substring(5).trim();

                // add synonym corresponding to this xref
                TermSynonym tsynInRgd = null;
                for( TermSynonym tsyn: synonymsInRgd ){
                    if( xref.startsWith(tsyn.getName()) ) {
                        tsynInRgd = tsyn;
                        break;
                    }
                }
                if( tsynInRgd!=null ) {
                    synonymInRgdCount++;
                } else {
                    synonymInsertedCount++;

                    TermSynonym syn = new TermSynonym();
                    syn.setSource("RGD");
                    syn.setTermAcc(termAcc);
                    syn.setCreatedDate(new Date());
                    syn.setType("xref");
                    syn.setLastModifiedDate(new Date());
                    syn.setName(xref);
                    odao.insertTermSynonym(syn);
                    synonymsInRgd.add(syn);
                }


                // delete this xref from the term
                for( TermXRef xrefInRgd: xrefsInRgd ) {
                    String inRgd = xrefInRgd.getXrefType()+":"+xrefInRgd.getXrefValue();
                    if( xref.startsWith(inRgd) ) {
                        odao.deleteTermXRef(xrefInRgd);
                        xrefsDeleted++;
                    }
                }
            }
        }
        in.close();

        System.out.println("synonyms inserted: "+synonymInsertedCount);
        System.out.println("synonyms already in rgd: "+synonymInRgdCount);
        System.out.println("xrefs deleted: "+xrefsDeleted);

    }
}
