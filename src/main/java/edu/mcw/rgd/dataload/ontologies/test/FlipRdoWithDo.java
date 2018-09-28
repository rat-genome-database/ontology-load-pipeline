package edu.mcw.rgd.dataload.ontologies.test;

import edu.mcw.rgd.dao.impl.OntologyXDAO;
import edu.mcw.rgd.dao.spring.StringListQuery;
import edu.mcw.rgd.datamodel.ontologyx.Term;
import edu.mcw.rgd.datamodel.ontologyx.TermSynonym;
import edu.mcw.rgd.process.Utils;

import java.util.*;

/**
 * Created by mtutaj on 12/13/2017.
 */
public class FlipRdoWithDo {
    public static void main(String[] args) throws Exception {

        updateOMIAFile();
        updatePortalTermSets();
        updateOntExtRelationship();

        OntologyXDAO odao = new OntologyXDAO();
        List<Term> rdoTerms = odao.getActiveTerms("RDO");
        System.out.println("flipping over RDO terms: "+rdoTerms.size());
        Collections.shuffle(rdoTerms);

        int doPlusId = 9000000; // next available DO+ id
        int i=1;
        for( Term t: rdoTerms ) {
            System.out.println(i+". "+t.getAccId()+" "+t.getTerm());
            i++;
            String rdoId = t.getAccId();

            List<TermSynonym> synonyms = odao.getTermSynonyms(t.getAccId());

            // find a DOID among synonyms;
            String doid = null;
            boolean rdoSynonym = false;
            for( TermSynonym tsyn: synonyms ) {
                if( tsyn.getName().startsWith("DOID:") ) {
                    doid = tsyn.getName();
                } else if( tsyn.getName().equals(rdoId) ) {
                        rdoSynonym = true;
                }
            }

            // if not found, generate a custom DOID
            if( doid==null ) {
                doid = "DOID:"+doPlusId;
                doPlusId++;
            }

            // change term acc to DOID, and insert new term
            t.setAccId(doid);
            Term inRgd = odao.getTermByAccId(doid);
            if( inRgd==null ) {
                odao.insertTerm(t);
            }

            // drop any DOID synonyms, and insert RDO synonym
            boolean wasUpdated = rdoSynonym;
            for( TermSynonym tsyn: synonyms ) {
                if( tsyn.getName().startsWith("DOID:") ) {
                    if( !wasUpdated ) {
                        tsyn.setTermAcc(doid);
                        tsyn.setName(rdoId);
                        tsyn.setType("primary_id");
                        odao.updateTermSynonym(tsyn);
                        wasUpdated = true;
                    } else {
                        odao.dropTermSynonym(tsyn.getKey());
                    }
                }
            }
            if( !wasUpdated ) {
                TermSynonym tsyn = new TermSynonym();
                tsyn.setTermAcc(doid);
                tsyn.setName(rdoId);
                tsyn.setType("primary_id");
                tsyn.setSource("RGD");
                odao.insertTermSynonym(tsyn);
            }

            /*
            String sql = "UPDATE ont_xrefs SET term_acc=? WHERE term_acc=?";
            odao.update(sql, doid, rdoId);
            sql = "UPDATE ont_synonyms SET term_acc=? WHERE term_acc=?";
            odao.update(sql, doid, rdoId);
            sql = "UPDATE ont_term_stats SET term_acc=? WHERE term_acc=?";
            odao.update(sql, doid, rdoId);
            sql = "UPDATE ont_term_stats2 SET term_acc=? WHERE term_acc=?";
            odao.update(sql, doid, rdoId);
            sql = "UPDATE ont_dag SET parent_term_acc=? WHERE parent_term_acc=?";
            odao.update(sql, doid, rdoId);
            sql = "UPDATE ont_dag SET child_term_acc=? WHERE child_term_acc=?";
            odao.update(sql, doid, rdoId);
            sql = "UPDATE ont_dag_custom SET parent_term_acc=? WHERE parent_term_acc=?";
            odao.update(sql, doid, rdoId);
            sql = "UPDATE ont_dag_custom SET child_term_acc=? WHERE child_term_acc=?";
            odao.update(sql, doid, rdoId);
            sql = "UPDATE full_annot SET term_acc=? WHERE term_acc=?";
            try {
                odao.update(sql, doid, rdoId);
            } catch(Exception e){
                System.out.println("flip problem for "+rdoId+" "+doid);
            }
            sql = "DELETE FROM ont_terms WHERE term_acc=?";
            odao.update(sql, rdoId);
            */
            String sql = "BEGIN "
                +"UPDATE ont_xrefs SET term_acc=? WHERE term_acc=?;"
                +"UPDATE ont_synonyms SET term_acc=? WHERE term_acc=?;"
                + "UPDATE ont_term_stats SET term_acc=? WHERE term_acc=?;"
                + "UPDATE ont_term_stats2 SET term_acc=? WHERE term_acc=?;"
                + "UPDATE ont_dag SET parent_term_acc=? WHERE parent_term_acc=?;"
                + "UPDATE ont_dag SET child_term_acc=? WHERE child_term_acc=?;"
                + "UPDATE ont_dag_custom SET parent_term_acc=? WHERE parent_term_acc=?;"
                + "UPDATE ont_dag_custom SET child_term_acc=? WHERE child_term_acc=?;"
                +"END;";
            odao.update(sql, doid, rdoId, doid, rdoId, doid, rdoId, doid, rdoId, doid, rdoId,
                    doid, rdoId, doid, rdoId, doid, rdoId);
            try {
                sql = "UPDATE full_annot SET term_acc=? WHERE term_acc=?";
                odao.update(sql, doid, rdoId);
            } catch(Exception e){
                System.out.println("flip problem for "+rdoId+" "+doid);
            }
            sql = "DELETE FROM ont_terms WHERE term_acc=?";
            odao.update(sql, rdoId);
        }
    }

    static void updateOntExtRelationship() throws Exception {
        OntologyXDAO odao = new OntologyXDAO();
        String sql = "SELECT DISTINCT term_acc1 FROM ont_ext_relationship WHERE term_acc1 like 'RDO%'";
        List<String> rdoIds = StringListQuery.execute(odao, sql);
        System.out.println("RDO ids: "+rdoIds.size());
        for( String rdoId: rdoIds ) {
            List<Term> terms = odao.getTermsBySynonym("RDO", rdoId, "exact");
            if( terms.isEmpty() ) {
                System.out.println("no match for term "+rdoId);
            } else {
                for( Term term: terms ) {
                    odao.update("UPDATE ont_ext_relationship SET term_acc1=? WHERE term_acc1=?", term.getAccId(), rdoId);
                }
            }
        }
        System.exit(0);
    }

    static void updatePortalTermSets() throws Exception {
        OntologyXDAO odao = new OntologyXDAO();
        String sql = "SELECT DISTINCT term_acc FROM portal_termset1 WHERE term_acc like 'RDO%'";
        List<String> rdoIds = StringListQuery.execute(odao, sql);
        System.out.println("RDO ids: "+rdoIds.size());
        Collections.shuffle(rdoIds);

        for( String rdoId: rdoIds ) {
            List<Term> terms = odao.getTermsBySynonym("RDO", rdoId, "exact");
            // skip inactive terms
            Iterator<Term> it = terms.iterator();
            while( it.hasNext() ) {
                Term t = it.next();
                if( t.isObsolete() ) {
                    it.remove();
                }
            }
            if( terms.isEmpty() ) {
                System.out.println("no match for term "+rdoId);
            } else if( terms.size()==1 ){
                for( Term term: terms ) {
                    odao.update("UPDATE portal_termset1 SET term_acc=?,ont_term_name=? WHERE term_acc=?",
                            term.getAccId(), term.getTerm(), rdoId);
                }
            } else {
                System.out.println("multis for term "+rdoId);
            }
        }
        System.exit(0);
    }

    static void updateOMIAFile() throws Exception {
        OntologyXDAO odao = new OntologyXDAO();
        String content = Utils.readFileAsString("/tmp/omia.txt");
        String[] lines = content.split("[\\n]");
        for( String line: lines ) {
            String rdoId = line.trim();
            List<Term> terms = odao.getTermsBySynonym("RDO", rdoId, "exact");
            // skip inactive terms
            Iterator<Term> it = terms.iterator();
            while( it.hasNext() ) {
                Term t = it.next();
                if( t.isObsolete() ) {
                    it.remove();
                }
            }
            if( terms.size()!=1 ) {
                System.out.println(rdoId);
            } else {
                System.out.println(terms.get(0).getAccId());
            }
        }
        System.exit(0);
    }
}
