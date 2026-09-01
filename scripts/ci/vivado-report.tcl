set project_file "project/loongson.xpr"
if {$argc >= 1} {
    set project_file [lindex $argv 0]
}
if {![file exists $project_file]} {
    puts stderr "Vivado project not found: $project_file"
    exit 2
}

open_project $project_file
set run_status [get_property STATUS [get_runs impl_1]]
set run_progress [get_property PROGRESS [get_runs impl_1]]
puts "Implementation status: $run_status"
puts "Implementation progress: $run_progress"
if {![string match "*Complete*" $run_status] || $run_progress ne "100%"} {
    puts stderr "Implementation did not complete successfully"
    exit 1
}

open_run impl_1
set report_dir "project/loongson.runs/impl_1"
if {$argc >= 2} {
    set report_dir [lindex $argv 1]
}
file mkdir $report_dir
report_timing_summary -delay_type min_max -report_unconstrained \
    -check_timing_verbose -file "$report_dir/timing_summary.rpt"
report_utilization -hierarchical -hierarchical_depth 3 \
    -file "$report_dir/utilization_hierarchical.rpt"
report_drc -file "$report_dir/drc.rpt"
report_methodology -file "$report_dir/methodology.rpt"

set bitstreams [glob -nocomplain "$report_dir/*.bit"]
if {[llength $bitstreams] != 1} {
    puts stderr "Expected one generated bitstream, found [llength $bitstreams]"
    exit 1
}
puts "Generated bitstream: [lindex $bitstreams 0]"
close_project
