# update stats for every ontology
#
. /etc/profile
APPDIR=/home/rgddata/pipelines/OntologyLoad
cd $APPDIR

#update stats for all ontologies (affected table ONT_TERM_STATS2)
ontologies=( "XCO" "MMO" "NBO" "MI" "SO" "CL" "PW" "ZFA" "EFO" "CMO" "VT" "MA" "RS" "MP" "HP" "UBERON" "GO" "CHEBI" )

for ontology in "${ontologies[@]}"; do
    #update ontology and term stats
    $APPDIR/run_single.sh "$ontology" -skip_downloads $1 $2 $3 $4 $5 $6 $7 $8
    echo ""
done

