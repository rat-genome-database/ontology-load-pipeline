# launch ontology loading sequentially for every ontology, except RDO
# if loading of any ontology fails, it won't affect loading of other ontologies
# that's why this script is preferred to run over run.sh script
#
APP=/home/rgddata/pipelines/OntologyLoad

ontologies=( "XCO" "MMO" "NBO" "MI" "SO" "CL" "PW" "ZFA" "CMO" "VT" "MA" "RS" "MP" "HP" "UBERON" "GO" "CHEBI" )

for ontology in "${ontologies[@]}"; do
    #update ontology and term stats
    $APP/run_single.sh "$ontology" $1 $2 $3 $4 $5 $6 $7 $8
    #update gviewer stats for ontology
    $APP/run_single.sh "$ontology" -gviewer_stats
    echo ""
done


