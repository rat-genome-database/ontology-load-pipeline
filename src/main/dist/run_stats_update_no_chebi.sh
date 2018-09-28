# update stats for every ontology, except CHEBI (affects table ONT_TERM_STATS2)
#
. /etc/profile
APPDIR=/home/rgddata/pipelines/OntologyLoad
cd $APPDIR

ontologies=( "RDO" "GO" "MP" "HP" "MMO" "CMO" "XCO" "RS" "PW" )

for ontology in "${ontologies[@]}"; do
    #update ontology term stats
    $APPDIR/run_single.sh "$ontology" -skip_downloads -qc_thread_count=12
    echo ""
done
