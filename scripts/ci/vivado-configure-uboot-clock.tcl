if {$argc != 2} {
    puts stderr "usage: vivado-configure-uboot-clock.tcl REQUESTED_MHZ OUTPUT_FILE"
    exit 2
}

set requested_mhz [lindex $argv 0]
set output_file [lindex $argv 1]
if {![string is double -strict $requested_mhz] || $requested_mhz <= 0} {
    puts stderr "requested_mhz must be a positive number"
    exit 2
}

set project_file "system_run.xpr"
if {![file exists $project_file]} {
    puts stderr "Vivado project not found: $project_file"
    exit 2
}

open_project $project_file

set project_dir [file dirname [file normalize $project_file]]
set chiplab_dir [file normalize [file join $project_dir .. .. ..]]
set peripheral_rtl {
    IP/APB_DEV/nt35510_apb.v
    IP/APB_DEV/ps2_apb.v
    IP/USB/chiplab_usb_host.v
    IP/USB/ue11/usbh_crc16.v
    IP/USB/ue11/usbh_crc5.v
    IP/USB/ue11/usbh_fifo.v
    IP/USB/ue11/usbh_host.v
    IP/USB/ue11/usbh_sie.v
    IP/USB/ue11/usbh_top.v
}
foreach relative_path $peripheral_rtl {
    set peripheral_path [file join $chiplab_dir $relative_path]
    if {![file exists $peripheral_path]} {
        puts stderr "Missing Chiplab peripheral RTL: $peripheral_path"
        exit 1
    }
    if {[llength [get_files -quiet -all "*/[file tail $peripheral_path]"]] == 0} {
        add_files -norecurse -fileset sources_1 $peripheral_path
    }
}

set source_filesets [get_filesets -quiet sources_1]
if {[llength $source_filesets] != 1} {
    puts stderr "Expected exactly one sources_1 fileset, found [llength $source_filesets]"
    exit 1
}
set source_fileset [lindex $source_filesets 0]
set usb_include_dir [file join $chiplab_dir IP USB ue11]
set include_dirs [get_property include_dirs $source_fileset]
if {[lsearch -exact $include_dirs $usb_include_dir] < 0} {
    lappend include_dirs $usb_include_dir
    set_property include_dirs $include_dirs $source_fileset
}
update_compile_order -fileset $source_fileset

set synth_strategy "Flow_PerfOptimized_high"
set impl_strategy "Performance_ExplorePostRoutePhysOpt"
foreach {run_name strategy} [list synth_1 $synth_strategy impl_1 $impl_strategy] {
    set runs [get_runs -quiet $run_name]
    if {[llength $runs] != 1} {
        puts stderr "Expected exactly one $run_name run, found [llength $runs]"
        exit 1
    }
    set run [lindex $runs 0]
    set_property STRATEGY $strategy $run
    set configured_strategy [get_property STRATEGY $run]
    if {$configured_strategy ne $strategy} {
        puts stderr "Failed to apply $run_name strategy: requested=$strategy configured=$configured_strategy"
        exit 1
    }
}

set clock_ips [get_ips -quiet clk_pll_33]
if {[llength $clock_ips] != 1} {
    puts stderr "Expected exactly one clk_pll_33 IP, found [llength $clock_ips]"
    exit 1
}
set clock_ip [lindex $clock_ips 0]

set_property CONFIG.CLKOUT1_REQUESTED_OUT_FREQ $requested_mhz $clock_ip
set configured_mhz [get_property CONFIG.CLKOUT1_REQUESTED_OUT_FREQ $clock_ip]
if {double($configured_mhz) != double($requested_mhz)} {
    puts stderr "Failed to apply requested U-Boot CPU clock: requested=$requested_mhz configured=$configured_mhz"
    exit 1
}

reset_target all $clock_ip
generate_target all $clock_ip

set output [open $output_file w]
puts $output "requested_mhz=$configured_mhz"
puts $output "synth_strategy=$synth_strategy"
puts $output "impl_strategy=$impl_strategy"
puts $output "peripheral_rtl_count=[llength $peripheral_rtl]"
close $output

puts "Configured clk_pll_33 clk_out1: requested=$configured_mhz MHz; synth=$synth_strategy; impl=$impl_strategy; peripheral_rtl=[llength $peripheral_rtl]"
close_project
