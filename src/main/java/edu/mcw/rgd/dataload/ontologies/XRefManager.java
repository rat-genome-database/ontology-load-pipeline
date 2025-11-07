package edu.mcw.rgd.dataload.ontologies;

import edu.mcw.rgd.datamodel.ontologyx.TermXRef;
import edu.mcw.rgd.process.Utils;
import org.apache.commons.collections4.ListUtils;

import java.util.*;

/**
 * User: mtutaj
 * Date: 8/21/13
 * class to handle dbxrefs from obo term definitions
 */
public class XRefManager {

    private final Set<TermXRef> incomingXRefs = new HashSet<>();
    private List<TermXRef> matchingXRefs;
    private List<TermXRef> forInsertXRefs;
    private List<TermXRef> forDeleteXRefs;
    private List<TermXRef> descChangedXRefs;

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

        // set term acc for incoming xrefs
        for( TermXRef xref: incomingXRefs ) {
            xref.setTermAcc(termAcc);
        }

        List<TermXRef> incomingXRefsList = new ArrayList<>(inRgdXRefs);
        forInsertXRefs = ListUtils.subtract(incomingXRefsList, inRgdXRefs);
        forDeleteXRefs = ListUtils.subtract(inRgdXRefs, incomingXRefsList);
        matchingXRefs = ListUtils.intersection(incomingXRefsList, inRgdXRefs);

        // find any xrefs with changed definitions
        for( TermXRef inRgdXRef: matchingXRefs ) {
            TermXRef incomingXRef = getMatchingXRef(inRgdXRef, incomingXRefsList);
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

    // return nr of incoming urls normalized
    public int normalizeIncomingUrls() {

        int normalizedCount = 0;
        for( TermXRef xref: incomingXRefs ) {

            // remove 'url:' prefix from urls like 'url:httpxxxx'
            if( xref.getXrefValue().startsWith("url:http") ) {
                xref.setXrefValue( xref.getXrefValue().substring(4) );
            }

            if( normalize(xref, "https://www.ncbi.nlm.nih.gov/pubmed/?term=", "PMID:") ) {
                normalizedCount++;
            }
            else
            // convert "https://pubmed.ncbi.nlm.nih.gov/32701516/" into "PMID:32701516"
            if( normalize(xref, "https://pubmed.ncbi.nlm.nih.gov/", "PMID:") ) {
                normalizedCount++;
            }
            else
            if( normalize(xref, "https://www.ncbi.nlm.nih.gov/pubmed/", "PMID:") ) {
                normalizedCount++;
            }
            else
            if( normalize(xref, "https://www.omim.org/entry/", "MIM:") ) {
                normalizedCount++;
            }
            else
            if( normalize(xref, "http://omim.org/entry/", "MIM:") ) {
                normalizedCount++;
            }
            else
            if( xref.getXrefValue().contains("//www.orpha.net/consor/cgi-bin/OC_Exp.php") ) {
                String url = xref.getXrefValue();
                int beginPos = url.indexOf("Expert=");
                if( beginPos > 0 ) {
                    String acc = "";
                    for (int pos = beginPos + "Expert=".length(); pos < url.length(); pos++) {
                        char c = url.charAt(pos);
                        if (Character.isDigit(c)) {
                            acc += c;
                        } else {
                            break;
                        }
                    }
                    if (!acc.isEmpty()) {
                        xref.setXrefValue("ORDO:" + acc);
                        normalizedCount++;
                    }
                }
            }
        }
        return normalizedCount;
    }

    private boolean normalize( TermXRef xref, String urlPrefix, String accPrefix ) {
        if( xref.getXrefValue().startsWith(urlPrefix) ) {
            int pmidStartPos = urlPrefix.length();
            int pmidEndPos = xref.getXrefValue().indexOf('/', pmidStartPos);
            if( pmidEndPos<0 ) {
                pmidEndPos = xref.getXrefValue().indexOf('?', pmidStartPos);
            }
            if( pmidEndPos<0 ) {
                pmidEndPos = xref.getXrefValue().indexOf('&', pmidStartPos);
            }

            String pmid;
            if( pmidEndPos<0 ) {
                pmid = xref.getXrefValue().substring(pmidStartPos);
            } else {
                pmid = xref.getXrefValue().substring(pmidStartPos, pmidEndPos);
            }
            xref.setXrefValue(accPrefix + pmid);
            return true;
        }
        return false;
    }

}
