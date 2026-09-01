if {$argc != 2} {
    puts stderr "usage: vivado-configure-clock.tcl REQUESTED_MHZ OUTPUT_FILE"
    exit 2
}

set requested_mhz [lindex $argv 0]
set output_file [lindex $argv 1]
if {![string is double -strict $requested_mhz] || $requested_mhz <= 0} {
    puts stderr "requested_mhz must be a positive number"
    exit 2
}

set project_file "project/loongson.xpr"
if {![file exists $project_file]} {
    puts stderr "Vivado project not found: $project_file"
    exit 2
}

open_project $project_file
set clock_ips [get_ips -quiet clk_pll]
if {[llength $clock_ips] != 1} {
    puts stderr "Expected exactly one clk_pll IP, found [llength $clock_ips]"
    exit 1
}
set clock_ip [lindex $clock_ips 0]

set_property CONFIG.CLKOUT1_REQUESTED_OUT_FREQ $requested_mhz $clock_ip
set configured_mhz [get_property CONFIG.CLKOUT1_REQUESTED_OUT_FREQ $clock_ip]
if {double($configured_mhz) != double($requested_mhz)} {
    puts stderr "Failed to apply requested CPU clock: requested=$requested_mhz configured=$configured_mhz"
    exit 1
}

reset_target all $clock_ip
generate_target all $clock_ip

set output [open $output_file w]
puts $output "requested_mhz=$configured_mhz"
close $output

puts "Configured clk_pll: requested=$configured_mhz MHz"
close_project
