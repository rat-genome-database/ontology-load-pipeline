package edu.mcw.rgd.dataload.ontologies.test;

import edu.mcw.rgd.dao.AbstractDAO;
import edu.mcw.rgd.dao.spring.StringListQuery;
import edu.mcw.rgd.dao.spring.ontologyx.OntologyQuery;
import edu.mcw.rgd.dao.spring.ontologyx.TermQuery;
import edu.mcw.rgd.dataload.ontologies.OntologyDAO;
import edu.mcw.rgd.datamodel.ontologyx.Term;
import edu.mcw.rgd.datamodel.ontologyx.TermSynonym;
import edu.mcw.rgd.process.Utils;

import java.util.List;

public class ObsoleteEfoHandler {
    public static void main(String[] args) throws Exception {

        int successfulReplacements = 0;
        int failedReplacements = 0;

        AbstractDAO adao = new AbstractDAO();
        OntologyDAO dao = new OntologyDAO();

        String sql = """
                select term_acc from ont_terms t where exists (
                  select 1 from ont_synonyms s1 where t.term_acc=s1.term_acc and synonym_name like 'EFO%'
                    and exists(select 1 from ont_terms t2 where s1.synonym_name=t2.term_acc)
                    and exists(select 1 from ont_terms et where et.term_acc=synonym_name and et.is_obsolete<>0)
                  ) and ont_id not in('EFO','UBERON')
                order by dbms_random.random
                """;
        List<String> termAccessions = StringListQuery.execute(adao, sql);
        System.out.println("processing "+termAccessions.size()+" terms with obsolete EFO accessions ...");
        for( String termAcc: termAccessions ) {

            String replacementTerm = null;

            List<TermSynonym> synonyms = dao.getTermSynonyms(termAcc);
            for( TermSynonym tsyn: synonyms ) {
                if( tsyn.getName().startsWith("EFO:") ) {
                    String efoTermAcc = tsyn.getName();
                    Term efoTerm = dao.getTerm(efoTermAcc);
                    if( efoTerm.isObsolete() ) {

                        // look for replacement OBA term in term description
                        final String phrase = "Project to replace EFO measurement branch with enriched OBA branch. Please use: ";
                        String efoTermDef = Utils.defaultString(efoTerm.getDefinition());
                        int phrasePos = efoTermDef.indexOf(phrase);
                        if( phrasePos>0 ) {
                            replacementTerm = efoTermDef.substring( phrasePos + phrase.length());
                            if( replacementTerm.startsWith("OBA:VT") ) {
                                replacementTerm = "VT:" + replacementTerm.substring(6);
                            }
                            System.out.println(termAcc+": "+efoTermAcc+"  replaced with "+replacementTerm);
                            successfulReplacements++;
                            break;
                        }

                        boolean foundReplacement = false;
                        List<TermSynonym> efoTermSynonyms = dao.getTermSynonyms(efoTermAcc);
                        for( TermSynonym ts: efoTermSynonyms ) {
                            if( ts.getType().equals("replaced_by") ) {

                                replacementTerm = ts.getName();
                                String ordoPhrase = "http://www.orpha.net/ORDO/Orphanet_";
                                if( replacementTerm.startsWith(ordoPhrase) ) {
                                    replacementTerm = "ORDO:"+replacementTerm.substring(ordoPhrase.length()).trim();
                                    foundReplacement = true;
                                    break;
                                }

                                ordoPhrase = "http://www.ebi.ac.uk/efo/EFO_";
                                if( replacementTerm.startsWith(ordoPhrase) ) {
                                    replacementTerm = "EFO:" + replacementTerm.substring(ordoPhrase.length()).trim();
                                    foundReplacement = true;
                                    break;
                                }

                                ordoPhrase = "http://purl.obolibrary.org/obo/MONDO_";
                                if( replacementTerm.startsWith(ordoPhrase) ) {
                                    replacementTerm = "MONDO:" + replacementTerm.substring(ordoPhrase.length()).trim();
                                    foundReplacement = true;
                                    break;
                                }

                                ordoPhrase = "http://purl.obolibrary.org/obo/HP_";
                                if( replacementTerm.startsWith(ordoPhrase) ) {
                                    replacementTerm = "HP:" + replacementTerm.substring(ordoPhrase.length()).trim();
                                    foundReplacement = true;
                                    break;
                                }
                            }
                        }
                        if( foundReplacement ) {
                            System.out.println(termAcc + ": " + efoTermAcc + "  replaced with " + replacementTerm);
                            successfulReplacements++;
                            break;
                        } else {
                            System.out.println("tdo");
                        }
                    }
                }
            }

            if( replacementTerm==null ) {
                System.out.println(termAcc + ":   WARNING -- no replacements!");
                failedReplacements++;
            }
        }

        System.out.println("terms replaced: "+successfulReplacements);
        System.out.println("terms failed to replace: "+failedReplacements);
    }
}
