package edu.mcw.rgd.dataload.ontologies;

import edu.mcw.rgd.datamodel.ontologyx.TermXRef;
import edu.mcw.rgd.process.Utils;
import org.apache.commons.collections.ListUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Created by IntelliJ IDEA.
 * User: mtutaj
 * Date: 8/21/13
 * Time: 3:38 PM
 * class to handle dbxrefs from obo term definitions
 */
public class XRefManager {

    private List<TermXRef> incomingXRefs = new ArrayList<>();
    private List<TermXRef> matchingXRefs;
    private List<TermXRef> forInsertXRefs;
    private List<TermXRef> forDeleteXRefs;
    private List<TermXRef> descChangedXRefs;

    public List<TermXRef> getIncomingXRefs() {
        return incomingXRefs;
    }

    public void addIncomingXRefs(List<TermXRef> incomingXRefs) {
        if( incomingXRefs!=null )
            this.incomingXRefs.addAll(incomingXRefs);
    }

    public List<TermXRef> getForInsertXRefs() {
        return forInsertXRefs;
    }

    public List<TermXRef> getForDeleteXRefs() {
        return forDeleteXRefs;
    }

    public List<TermXRef> getMatchingXRefs() {
        return matchingXRefs;
    }

    public List<TermXRef> getDescChangedXRefs() {
        return descChangedXRefs==null ? Collections.<TermXRef>emptyList() : descChangedXRefs;
    }

    public void qc(String termAcc, List<TermXRef> inRgdXRefs) throws Exception {

        descChangedXRefs = null;

        if( incomingXRefs==null ) {

            forInsertXRefs = Collections.emptyList();
            forDeleteXRefs = inRgdXRefs;
            matchingXRefs = Collections.emptyList();
            return;
        }

        // set term acc for incoming xrefs
        for( TermXRef xref: incomingXRefs ) {
            xref.setTermAcc(termAcc);
        }

        forInsertXRefs = ListUtils.subtract(incomingXRefs, inRgdXRefs);
        forDeleteXRefs = ListUtils.subtract(inRgdXRefs, incomingXRefs);
        matchingXRefs = ListUtils.intersection(incomingXRefs, inRgdXRefs);

        // find any xrefs with changed definitions
        for( TermXRef inRgdXRef: matchingXRefs ) {
            TermXRef incomingXRef = getMatchingXRef(inRgdXRef, incomingXRefs);
            if( !Utils.stringsAreEqualIgnoreCase(inRgdXRef.getXrefDescription(), incomingXRef.getXrefDescription()) ) {
                // xref with changed description found
                if( descChangedXRefs==null )
                    descChangedXRefs = new ArrayList<>();
                inRgdXRef.setXrefDescription(incomingXRef.getXrefDescription());
                descChangedXRefs.add(inRgdXRef);
            }
        }
    }

    TermXRef getMatchingXRef(TermXRef xref, List<TermXRef> xrefs) {
        for( TermXRef xref2: xrefs ) {
            if( xref2.equals(xref) ) {
                return xref2;
            }
        }
        return null;
    }
}
