# update stats for every ontology, except CHEBI (affects table ONT_TERM_STATS2)
#
. /etc/profile
APPDIR=/home/rgddata/pipelines/OntologyLoad
cd $APPDIR

ontologies=( "RDO" "GO" "MP" "HP" "MMO" "CMO" "XCO" "RS" "PW" )

LOGFILE=$APPDIR/stats_update_no_chebi.log
echo "" > $LOGFILE

for ontology in "${ontologies[@]}"; do
    #update ontology term stats
    $APPDIR/run_single.sh "$ontology" -skip_downloads >> $LOGFILE
done

echo "===" >> $LOGFILE
echo "DONE!" >> $LOGFILE

mailx -s "[$SERVER] Update Ontology term stats, no CHEBI" mtutaj@mcw.edu < $LOGFILE
