package edu.mcw.rgd.dataload.ontologies.test;

import edu.mcw.rgd.dao.impl.AnnotationDAO;
import edu.mcw.rgd.dao.impl.OntologyXDAO;
import edu.mcw.rgd.dao.spring.StringMapQuery;
import edu.mcw.rgd.dataload.ontologies.OntologyDAO;
import edu.mcw.rgd.datamodel.ontologyx.Term;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.*;

/**
 * Created by mtutaj on 4/27/2016.
 * <p>
 * given list of rgd ids, extract top level diseases
 */
public class TopLevelDiseases {

    public static void main(String[] args) throws Exception {

        OntologyXDAO odao = new OntologyXDAO();
        AnnotationDAO adao = new AnnotationDAO();

        BufferedReader in = new BufferedReader(new FileReader(args[0]));
        BufferedWriter out = new BufferedWriter(new FileWriter(args[1]));

        int i = 0;
        String line;
        String msg;
        while( (line=in.readLine())!=null ) {
            if( line.isEmpty() ) {
                out.newLine();
                continue;
            }

            int geneRgdId = Integer.parseInt(line);
            List<StringMapQuery.MapPair> diseaseAnnots = adao.getAnnotationTermAccIds(geneRgdId, "D");
            Set<String> topLevelDiseases = new TreeSet<>();
            for( StringMapQuery.MapPair pair: diseaseAnnots ) {
                List<StringMapQuery.MapPair> topLevelDiseaseTerms = odao.getTopLevelDiseaseTerms(pair.keyValue);
                for( StringMapQuery.MapPair pair2: topLevelDiseaseTerms ) {
                    topLevelDiseases.add(pair2.keyValue);
                }
            }

            // sort top level diseases by annot count descending
            final Map<String,Integer> hitMap = new HashMap<>();
            for( StringMapQuery.MapPair pair: diseaseAnnots ) {
                String rdoAcc = pair.keyValue;
                for( String topAcc: topLevelDiseases ) {
                    if( topAcc.equals(rdoAcc) || odao.isDescendantOf(rdoAcc, topAcc) ) {
                        // increment count
                        Integer cnt = hitMap.get(topAcc);
                        if( cnt==null ) {
                            cnt = 1;
                        } else {
                            cnt++;
                        }
                        hitMap.put(topAcc, cnt);
                    }
                }
            }

            List<String> topLevelDiseasesSorted = new ArrayList<>(topLevelDiseases);
            Collections.sort(topLevelDiseasesSorted, new Comparator<String>() {
                @Override
                public int compare(String o1, String o2) {
                    return hitMap.get(o2) - hitMap.get(o1);
                }
            });


            msg = line;
            for( String topAcc: topLevelDiseasesSorted ) {
                Term term = odao.getTermByAccId(topAcc);
                msg += "\t"+term.getAccId()+"\t"+term.getTerm()+" {"+hitMap.get(topAcc)+"}";
            }
            out.write(msg);
            out.newLine();

            System.out.println(++i);
        }

        in.close();
        out.close();
    }
}
