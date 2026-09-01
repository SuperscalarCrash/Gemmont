set jobs 1
if {$argc >= 1} {
    set jobs [lindex $argv 0]
}
if {![string is integer -strict $jobs] || $jobs < 1} {
    puts stderr "VIVADO_JOBS must be a positive integer"
    exit 2
}

set project_file "project/loongson.xpr"
if {$argc >= 2} {
    set project_file [lindex $argv 1]
}
set extra_sources {}
if {$argc >= 3} {
    set extra_sources [lrange $argv 2 end]
}
if {![file exists $project_file]} {
    puts stderr "Vivado project not found: $project_file"
    exit 2
}

proc normalize_path {path} {
    if {[catch {file normalize $path} normalized]} {
        return $path
    }
    return $normalized
}

open_project $project_file
foreach source_file $extra_sources {
    if {![file exists $source_file]} {
        puts stderr "Extra source not found: $source_file"
        exit 2
    }
    set normalized_source [file normalize $source_file]
    set source_tail [file tail $normalized_source]
    foreach existing_file [get_files -quiet -of_objects [get_filesets sources_1] "*$source_tail"] {
        set normalized_existing [normalize_path $existing_file]
        if {$normalized_existing ne $normalized_source} {
            remove_files $existing_file
        }
    }
    if {[llength [get_files -quiet $normalized_source]] == 0} {
        add_files -norecurse -fileset sources_1 $normalized_source
    }
    if {[string tolower [file extension $normalized_source]] in {.hex .mem}} {
        set_property file_type {Memory Initialization Files} \
            [get_files -quiet $normalized_source]
    }
}

set core_top_files {}
foreach project_file [get_files -quiet -of_objects [get_filesets sources_1]] {
    if {[string equal -nocase [file tail $project_file] "core_top.v"]} {
        lappend core_top_files $project_file
    }
}
if {[llength $core_top_files] != 1} {
    puts stderr "Expected exactly one core_top.v in sources_1, found [llength $core_top_files]"
    exit 2
}
set core_top_file [lindex $core_top_files 0]
set_property file_type SystemVerilog $core_top_file
if {[get_property file_type $core_top_file] ne "SystemVerilog"} {
    puts stderr "Failed to classify core_top.v as SystemVerilog"
    exit 2
}
puts "Gemmont core RTL: [normalize_path $core_top_file] (SystemVerilog)"
update_compile_order -fileset sources_1
launch_runs impl_1 -to_step write_bitstream -jobs $jobs
puts "Implementation launched in the background with $jobs job(s)"
close_project
