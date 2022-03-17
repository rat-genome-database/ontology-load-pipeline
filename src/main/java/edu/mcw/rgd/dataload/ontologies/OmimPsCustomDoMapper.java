package edu.mcw.rgd.dataload.ontologies;

import edu.mcw.rgd.datamodel.ontologyx.Ontology;
import edu.mcw.rgd.process.CounterPool;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class OmimPsCustomDoMapper {

    OntologyDAO dao = new OntologyDAO();

    public void run() throws Exception {

        List<String> incomingMappings = getIncomingMappings();
        List<String> inRgdMappings = dao.getChildParentDoMappings();

        List<String> forInsertMappings = new ArrayList<>(incomingMappings);
        forInsertMappings.removeAll(inRgdMappings);
        for( String mapping: forInsertMappings ) {
            String[] accs = mapping.split("[\\|]");
            dao.insertChildParentDoMapping(accs[0], accs[1]);
        }

        List<String> forDeleteMappings = new ArrayList<>(inRgdMappings);
        forDeleteMappings.removeAll(incomingMappings);
        for( String mapping: forDeleteMappings ) {
            String[] accs = mapping.split("[\\|]");
            dao.deleteChildParentDoMapping(accs[0], accs[1]);
        }

        System.out.println("incoming mappings: "+incomingMappings.size());
        System.out.println("in-RGD mappings: "+inRgdMappings.size());
        System.out.println("inserted mappings: "+forInsertMappings.size());
        System.out.println("deleted mappings: "+forDeleteMappings.size());
    }

    List<String> getIncomingMappings() throws Exception {

        Ontology ont = dao.getOntology("RDO");
        CounterPool counters = new CounterPool();
        ConcurrentHashMap<String, String> childToParentMap = new ConcurrentHashMap<>();

        List<String> doTermAccs = dao.getAllActiveTermDescendantAccIds(ont.getRootTermAcc());
        doTermAccs.parallelStream().forEach( doTermAcc -> {

            try {
                if (doTermAcc.startsWith("DOID:90") && doTermAcc.length() == 12) {

                    // RDO custom term: find a parent term by using OMIM:PS associations
                    String parentDoTermAcc = dao.getOmimPSTermAccForChildTerm(doTermAcc, counters);
                    if( parentDoTermAcc!=null ) {
                        // parent term cannot be a DO+ custom term
                        if( !(parentDoTermAcc.startsWith("DOID:90") && parentDoTermAcc.length()==12) ) {
                            childToParentMap.put(doTermAcc, parentDoTermAcc);
                        }
                    }
                }
            } catch( Exception e) {
                throw new RuntimeException(e);
            }
        });

        List<String> mappings = new ArrayList<>();
        for(Map.Entry<String,String> entry: childToParentMap.entrySet() ) {
            mappings.add(entry.getKey()+"|"+entry.getValue());
        }
        return mappings;
    }
}
