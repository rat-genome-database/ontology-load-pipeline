package edu.mcw.rgd.dataload.ontologies;

import edu.mcw.rgd.datamodel.ontologyx.TermStat;
import edu.mcw.rgd.datamodel.ontologyx.TermWithStats;
import edu.mcw.rgd.process.Utils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * @author mtutaj
 * Date: Apr 5, 2011
 */
public class TermStats {
    private String termAccId;
    private String[] xmlForTerm = new String[7]; // gviewer xml for term only
    private String[] xmlWithChilds = new String[7]; // gviewer xml for term with childs

    public TermWithStats term;

    public boolean xmlIsDirty = false;
    public boolean statsAreDirty = false;

    public List<TermStat> statsToBeAdded;
    public List<TermStat> statsToBeDeleted;
    public List<TermStat> statsToBeUpdated;

    public TermStats() {
        term = new TermWithStats((String)null);
    }

    public String getTermAccId() {
        return termAccId;
    }

    public void setTermAccId(String termAccId) {
        this.termAccId = termAccId;
        term.setAccId(termAccId);
    }

    public void setFilter(String filter) {
        term.setFilter(filter);
    }

    public String getFilter() {
        return term.getFilter();
    }

    public String getXmlForTerm(int speciesTypeKey) {
        return xmlForTerm[speciesTypeKey-1];
    }

    public void setXmlForTerm(String xml, int speciesTypeKey) {
        xmlForTerm[speciesTypeKey-1] = xml;
    }

    public String getXmlWithChilds(int speciesTypeKey) {
        return xmlWithChilds[speciesTypeKey-1];
    }

    public void setXmlWithChilds(String xml, int speciesTypeKey) {
        xmlWithChilds[speciesTypeKey-1] = xml;
    }

    public boolean equalsForGViewer(TermStats statsInRgd) {
        xmlIsDirty = false;
        for (int i = 0; i < 3; i++) {
            if (!Utils.stringsAreEqual(this.xmlForTerm[i], statsInRgd.xmlForTerm[i]) ||
                    !Utils.stringsAreEqual(this.xmlWithChilds[i], statsInRgd.xmlWithChilds[i])) {
                xmlIsDirty = true;
                break;
            }
        }
        return !xmlIsDirty;
    }

    public boolean equals(TermStats statsInRgd) {
        statsAreDirty = false;
        // determine which stats are to be added, and which to be deleted
        statsToBeAdded = new ArrayList<>();
        statsToBeDeleted = new ArrayList<>();
        statsToBeUpdated = new ArrayList<>();

        if( statsInRgd.term.getStats()!=null ) {
            for(Map.Entry<String,Integer> entry: statsInRgd.term.getStats().entrySet() ) {

                String statKey = entry.getKey();
                int inRgdValue = entry.getValue();

                int thisValue = this.term.getStat(statKey);
                if( thisValue!=inRgdValue ) {
                    TermStat ts = term.makeTermStat(this.term.getAccId(), statKey, thisValue, this.getFilter());

                    // skip aggregate stats for 'annotated_object_count'
                    if( (ts.getSpeciesTypeKey()==0 || ts.getObjectKey()==0) && ts.getStatName().equals("annotated_object_count") ) {
                        continue;
                    }

                    if( thisValue==0 ) {
                        statsToBeDeleted.add(ts);
                    } else {
                        statsToBeUpdated.add(ts);
                    }
                }
            }
        }

        //need to fix here--
        // new stats
        if( this.term.getStats()!=null ) {
            for(Map.Entry<String,Integer> entry: this.term.getStats().entrySet() ) {
                String statKey = entry.getKey();
                int incomingValue = entry.getValue();

                int thisValue = statsInRgd.term.getStat(statKey);

                if( thisValue==0 ) {
                    statsToBeAdded.add(this.term.makeTermStat(this.term.getAccId(), statKey, incomingValue, this.getFilter()));
                }
            }
        }

        if( !statsToBeAdded.isEmpty() || !statsToBeDeleted.isEmpty() || !statsToBeUpdated.isEmpty() ) {
            statsAreDirty = true;
        }
        return !statsAreDirty;
    }
}
