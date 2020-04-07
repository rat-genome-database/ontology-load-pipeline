package edu.mcw.rgd.dataload.ontologies;

import edu.mcw.rgd.dao.DataSourceFactory;
import edu.mcw.rgd.dao.impl.OntologyXDAO;
import edu.mcw.rgd.datamodel.ontologyx.Ontology;
import edu.mcw.rgd.datamodel.ontologyx.Term;
import edu.mcw.rgd.datamodel.ontologyx.TermSynonym;
import edu.mcw.rgd.datamodel.ontologyx.TermXRef;
import edu.mcw.rgd.process.Utils;

import javax.sql.DataSource;
import java.util.*;

/**
 * Created by IntelliJ IDEA.
 * User: mtutaj
 * Date: 9/17/15
 * Time: 2:53 PM
 * To change this template use File | Settings | File Templates.
 */
public class DoOntUtils {

    /*
    select * from ont_terms t
where is_obsolete=0 and term_acc like 'RDO:9%'
and (not exists(
   select 1 from ont_synonyms s,ont_xrefs x
   where s.term_acc=t.term_acc and ((s.synonym_name='OMIM:'||xref_value and xref_type='OMIM')
   or(s.synonym_name='MESH:'||xref_value and xref_type='MSH'))
   )
   )
     */
    public static void main(String[] args) throws Exception {
/*
        Dao doDAO = new Dao();

        OntologyDAO dao = new OntologyDAO();
        Set<String> doMeshIds = new HashSet<>();
        Set<String> doOmimIds = new HashSet<>();
        Map<String,List<Term>> doSynonyms = new HashMap<>();

        Ontology doOntology = doDAO.getOntology("DO");
        SynonymManager synonymManager = new SynonymManager();

        String id;
        String normalizedSynonym;

        for( Term t: doDAO.getAllActiveTermDescendants(doOntology.getRootTermAcc()) ) {
            System.out.println(t.getAccId()+" "+t.getTerm()+" ["+doMeshIds.size()+","+doOmimIds.size()+","+doSynonyms.size()+"]");

            System.out.print("   ");
            for(TermXRef xref: doDAO.getTermXRefs(t.getAccId())) {
                if( xref.getXrefType().equals("MSH") ) {
                    id = "MESH:"+xref.getXrefValue();
                    doMeshIds.add(id);
                    System.out.print(id+" ");
                }
                else if( xref.getXrefType().equals("OMIM") ) {
                    id = "OMIM:"+xref.getXrefValue();
                    doOmimIds.add(id);
                    System.out.print(id+" ");
                }
            }
            System.out.println();

            List<TermSynonym> synonyms = doDAO.getTermSynonyms(t.getAccId());
            // add term name as a fake synonym
            TermSynonym tsyn = new TermSynonym();
            tsyn.setTermAcc(t.getAccId());
            tsyn.setName(t.getTerm());
            synonyms.add(tsyn);

            for(TermSynonym syn: synonyms) {
                normalizedSynonym = synonymManager.process(syn.getName(), "syn");
                List<Term> doTerms = doSynonyms.get(normalizedSynonym);
                if( doTerms==null ) {
                    doTerms = new ArrayList<>();
                    doSynonyms.put(normalizedSynonym, doTerms);
                }
                doTerms.add(t);
            }
        }

        System.out.println();



        // report those RDO custom terms that do not have a matching MESH or OMIM id
        // nor they do not match by synonym
        int rdoCustomTermsMatchingMeshIdWithDo = 0;
        int rdoCustomTermsNotMatchingMeshIdWithDo = 0;
        int rdoCustomTermsMatchingOmimIdWithDo = 0;
        int rdoCustomTermsNotMatchingOmimIdWithDo = 0;
        int rdoCustomTermsMatchingSynonymWithDo = 0;
        int rdoCustomTermsNotMatchingSynonymWithDo = 0;
        int rdoCustomTermsMatchingWithDo = 0;
        List<Term> rdoCustomTermsNotMatchingWithDo = new ArrayList<>();

        for(Term t: dao.getAllActiveTermDescendants(dao.getOntology("RDO").getRootTermAcc()) ) {
            // skip non-custom terms
//            if( !t.getAccId().startsWith("RDO:9") )
  //              continue;

            boolean hasMeshMatchWithDo = false;
            boolean hasOmimMatchWithDo = false;
            boolean hasSynonymMatchWithDo = false;
            Set<String> matchingSyns = new HashSet<>();
            for(TermSynonym syn: dao.getTermSynonyms(t.getAccId())) {
                // check omim
                if( syn.getName().startsWith("OMIM:") ) {
                    if( doOmimIds.contains(syn.getName()) ) {
                        hasOmimMatchWithDo = true;
                    }
                }
                // check omim
                else if( syn.getName().startsWith("MESH:") ) {
                    if( doMeshIds.contains(syn.getName()) ) {
                        hasMeshMatchWithDo = true;
                    }
                } else {
                    normalizedSynonym = synonymManager.process(syn.getName(), "syn");
                    if( doSynonyms.containsKey(normalizedSynonym) ) {
                        hasSynonymMatchWithDo = true;
                        matchingSyns.add(normalizedSynonym);
                    }
                }
            }
            normalizedSynonym = synonymManager.process(t.getTerm(), "syn");
            if( doSynonyms.containsKey(normalizedSynonym) ) {
                hasSynonymMatchWithDo = true;
                matchingSyns.add(normalizedSynonym);
            }

            if( hasMeshMatchWithDo )
                rdoCustomTermsMatchingMeshIdWithDo ++;
            else
                rdoCustomTermsNotMatchingMeshIdWithDo ++;
            if( hasOmimMatchWithDo )
                rdoCustomTermsMatchingOmimIdWithDo ++;
            else
                rdoCustomTermsNotMatchingOmimIdWithDo ++;
            if( hasSynonymMatchWithDo ) {
                rdoCustomTermsMatchingSynonymWithDo ++;

                // print matching synonyms
                for( String nsyn: matchingSyns ) {
                    System.out.println(t.getAccId()+" "+t.getTerm());
                    for(Term doTerm: doSynonyms.get(nsyn)) {
                        System.out.println("   "+doTerm.getAccId()+" "+doTerm.getTerm());
                    }
                }
            } else
                rdoCustomTermsNotMatchingSynonymWithDo ++;

            if( hasMeshMatchWithDo || hasOmimMatchWithDo || hasSynonymMatchWithDo )
                rdoCustomTermsMatchingWithDo ++;
            else {
                rdoCustomTermsNotMatchingWithDo.add(t);
                //System.out.println(t.getAccId()+" "+t.getTerm());
            }
        }

        System.out.println("rdoCustomTermsMatchingMeshIdWithDo="+rdoCustomTermsMatchingMeshIdWithDo);
        System.out.println("rdoCustomTermsNotMatchingMeshIdWithDo="+rdoCustomTermsNotMatchingMeshIdWithDo);
        System.out.println("rdoCustomTermsMatchingOmimIdWithDo="+rdoCustomTermsMatchingOmimIdWithDo);
        System.out.println("rdoCustomTermsNotMatchingOmimIdWithDo="+rdoCustomTermsNotMatchingOmimIdWithDo);
        System.out.println("rdoCustomTermsMatchingSynonymWithDo="+rdoCustomTermsMatchingSynonymWithDo);
        System.out.println("rdoCustomTermsNotMatchingSynonymWithDo="+rdoCustomTermsNotMatchingSynonymWithDo);

        System.out.println("rdoCustomTermsMatchingWithDo="+rdoCustomTermsMatchingWithDo);
        System.out.println("rdoCustomTermsNotMatchingWithDo="+rdoCustomTermsNotMatchingWithDo.size());

        StringBuffer buf = new StringBuffer();
        for( Term t: rdoCustomTermsNotMatchingWithDo ) {
            buf.append(t.getAccId()).append(" ").append(t.getTerm()).append("\n");
        }
        Utils.writeStringToFile(buf.toString(), "termsNotMatchingWithDO.txt");
        new OboFileCreator().run("RDO", rdoCustomTermsNotMatchingWithDo);

 */
    }

    static class Dao extends OntologyXDAO {
        @Override
        public DataSource getDataSource() throws Exception{
            return DataSourceFactory.getInstance().getCarpeNovoDataSource();
        }
    }

}
