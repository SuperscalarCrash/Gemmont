module PerformanceCounterSink (
  input  wire        clock,
  input  wire        reset,
  input  wire [63:0] cycle,
  input  wire [31:0] fetchPc,
  input  wire [95:0] retirePc,
  input  wire [95:0] retireCounterValue,
  input  wire [2:0]  retireValid,
  input  wire [2:0]  retireCounter,
  input  wire        branchRetired,
  input  wire        mispredictRetired,
  input  wire        branchMispredictRetired,
  input  wire        otherRecovery,
  input  wire        h64LateCorrection,
  input  wire [2:0]  h64RenameFire,
  input  wire [2:0]  h64RenameEvaluated,
  input  wire [14:0] h64RenameRobIndex,
  input  wire [2:0]  h64RenameFastTaken,
  input  wire [2:0]  h64RenameNeuralTaken,
  input  wire [2:0]  h64RenameReliable,
  input  wire [2:0]  h64RenameOverride,
  input  wire [2:0]  h64DecodeFire,
  input  wire [14:0] h64DecodeRobIndex,
  input  wire [95:0] h64DecodePc,
  input  wire [11:0] h64DecodeToken,
  input  wire [8:0]  h64DecodeEpoch,
  input  wire [5:0]  h64DecodeWay,
  input  wire [191:0] h64DecodeHistory,
  input  wire [95:0] h64DecodePath,
  input  wire [32:0] h64DecodeScore,
  input  wire [5:0]  h64DecodePhtCounter,
  input  wire [2:0]  h64DecodeFastTaken,
  input  wire [2:0]  h64DecodeNeuralTaken,
  input  wire [2:0]  h64DecodeReliable,
  input  wire [2:0]  h64DecodeOverride,
  input  wire [2:0]  h64DecodeDirectionBias,
  input  wire [2:0]  h64RetireActualTaken,
  input  wire [4:0]  retireRobIndex,
  input  wire [2:0]  mispredictResolved,
  input  wire [14:0] mispredictResolvedRob,
  input  wire [2:0]  frontendValid,
  input  wire        dispatchBlocked,
  input  wire [5:0]  robOccupancy,
  input  wire [2:0]  integerIssueOccupancy,
  input  wire [1:0]  mulDivIssueOccupancy,
  input  wire [2:0]  memoryIssueOccupancy,
  input  wire [3:0]  storeBufferOccupancy,
  input  wire [2:0]  integerIssue,
  input  wire        mulDivIssue,
  input  wire        memoryIssue,
  input  wire        memoryIssueOperandsReady,
  input  wire        memoryIssueAddressReady,
  input  wire        memoryIssueDataReady,
  input  wire        memoryIssueLsuReady,
  input  wire [31:0] memoryIssueHeadPc,
  input  wire        speculativeWakeupFailed,
  input  wire        mem2Valid,
  input  wire [31:0] mem2Pc,
  input  wire        mem2CacheWait,
  input  wire        mem2StoreBufferWait,
  input  wire        mem2AuxWait,
  input  wire        instructionCacheRequest,
  input  wire        instructionCacheHit,
  input  wire        instructionCacheMiss,
  input  wire        instructionCacheMissBusy,
  input  wire        dataCacheRequest,
  input  wire        dataCacheHit,
  input  wire        dataCacheMiss,
  input  wire        dataCacheMissBusy,
  input  wire        dataCacheRefillBusy,
  input  wire        dataCachePostResponseRefillBusy,
  input  wire        dataCacheDirectRefill,
  input  wire        dataCacheEarlyResponse,
  input  wire        dataCacheDirtyWriteback,
  input  wire        dataCacheDirtyVictim,
  input  wire        dataCacheTailBlockedWouldHit,
  input  wire        dataCacheTailBlockedSameFillLine,
  input  wire        dataCacheTailBlockedNewMiss,
  input  wire        dataCacheTailBlockedStore,
  input  wire        dataCacheDirtyVictimCaptureBusy,
  input  wire        dataCacheDirtyVictimReadAddressWait,
  input  wire        dataCacheDirtyVictimResponseWait,
  input  wire        dataCacheLoadMiss,
  input  wire        dataCacheStoreMiss,
  input  wire        dataCacheLoadMissPlusOne,
  input  wire        dataCacheLoadMissMinusOne,
  input  wire        dataCacheLoadMissRepeat,
  input  wire        dataCachePrefetchCandidate,
  input  wire        dataCachePrefetchRequest,
  input  wire        dataCachePrefetchL2Hit,
  input  wire        dataCachePrefetchL2Miss,
  input  wire        dataCachePrefetchBufferHit,
  input  wire        dataCachePrefetchLate,
  input  wire        dataCachePrefetchDropped,
  input  wire        dataCachePrefetchDuplicate,
  input  wire        dataCachePrefetchPageSuppressed,
  input  wire        dataCachePrefetchCancelled,
  input  wire        dataCachePrefetchUseless,
  input  wire        l2InstructionRead,
  input  wire        l2InstructionHit,
  input  wire        l2InstructionMiss,
  input  wire        l2InstructionMissBusy,
  input  wire        l2DataRead,
  input  wire        l2DataHit,
  input  wire        l2DataDirectHit,
  input  wire        l2DataMiss,
  input  wire        l2DataMissBusy,
  input  wire        l2DataWrite,
  input  wire        l2DirtyWriteback,
  input  wire [31:0] l2ReadAddress,
  input  wire [31:0] l2DataWriteAddress,
  input  wire        l2ReadMissBusy,
  input  wire        l2WriteBusy,
  input  wire        l2DataPrefetchRead,
  input  wire        l2DataPrefetchHit,
  input  wire        l2DataPrefetchMiss,
  input  wire        l2DataPrefetchWait,
  input  wire        l2InstructionReadWait,
  input  wire [31:0] l2InstructionReadWaitAddress,
  input  wire        l2DataReadWait,
  input  wire [31:0] l2DataReadWaitAddress
);
`ifndef SYNTHESIS
  integer lane;
  longint unsigned retired_this_cycle;
  longint unsigned issued_this_cycle;
  longint unsigned heartbeat_interval;
  longint unsigned next_heartbeat;
  longint unsigned global_retired;
  longint unsigned counter_samples;
  longint unsigned windows;
  bit window_active;
  bit counter_pc_filter_enabled;
  bit [31:0] counter_pc_filter;
  bit counter_sample_range_enabled;
  longint unsigned counter_start_sample;
  longint unsigned counter_stop_sample;
  bit [31:0] counter_start_value;
  bit pc_histogram_enabled;
  bit profile_final_only;
  longint unsigned retired_pc_histogram [0:65535];
  longint unsigned rob_head_pc_histogram [0:65535];
  longint unsigned head_stall_pc_histogram [0:65535];
  longint unsigned mispredict_pc_histogram [0:65535];
  longint unsigned icache_wait_pc_histogram [0:65535];
  longint unsigned dcache_wait_pc_histogram [0:65535];
  longint unsigned memory_issue_blocked_pc_histogram [0:65535];
  longint unsigned memory_operand_wait_pc_histogram [0:65535];
  longint unsigned memory_address_wait_pc_histogram [0:65535];
  longint unsigned memory_data_wait_pc_histogram [0:65535];
  longint unsigned memory_lsu_wait_pc_histogram [0:65535];
  longint unsigned memory_recovery_wait_pc_histogram [0:65535];
  integer pc_histogram_index;

  // rtltrace-v2 is an observation-only binary event log. A decode record is
  // emitted when an H64-evaluated branch acquires a ROB slot at Rename;
  // retirement later supplies the actual outcome using that slot. Recovery
  // emits explicit squash records for all still-pending observations.
  localparam [7:0] RTLTRACE_EVENT_DECODE = 8'd1;
  localparam [7:0] RTLTRACE_EVENT_RETIRE = 8'd2;
  integer rtltrace_fd;
  bit rtltrace_enabled;
  bit [1023:0] rtltrace_path;
  bit rtltrace_pending_valid [0:31];
  bit rtltrace_pending_roi [0:31];
  bit rtltrace_pending_override [0:31];
  bit h64_pending_valid [0:31];
  bit h64_pending_fast_taken [0:31];
  bit h64_pending_neural_taken [0:31];
  bit h64_pending_reliable [0:31];
  bit h64_pending_override [0:31];
  bit [2:0] h64_retired_evaluated;
  bit [2:0] h64_retired_reliable;
  bit [2:0] h64_retired_disagreement;
  bit [2:0] h64_retired_override;
  bit [2:0] h64_retired_fast_correct;
  bit [2:0] h64_retired_neural_correct;

  longint unsigned profile_cycles;
  longint unsigned timer_cycles;
  longint unsigned retired_instructions;
  longint unsigned retire_zero_cycles;
  longint unsigned retire_one_cycles;
  longint unsigned retire_two_cycles;
  longint unsigned retire_three_cycles;
  longint unsigned branch_retired_count;
  longint unsigned mispredict_retired_count;
  longint unsigned branch_mispredict_count;
  longint unsigned other_recovery_count;
  longint unsigned h64_late_correction_count;
  longint unsigned h64_evaluated_count;
  longint unsigned h64_reliable_count;
  longint unsigned h64_disagreement_count;
  longint unsigned h64_override_count;
  longint unsigned h64_fast_correct_count;
  longint unsigned h64_neural_correct_count;
  longint unsigned h64_useful_count;
  longint unsigned h64_harmful_count;
  longint unsigned resolved_mispredict_count;
  longint unsigned resolve_to_redirect_cycles;
  longint unsigned resolve_to_redirect_max;
  longint unsigned resolve_missing_count;
  longint unsigned rob_squashed_instructions;

  longint unsigned frontend_empty_cycles;
  longint unsigned dispatch_blocked_cycles;
  longint unsigned rob_empty_cycles;
  longint unsigned rob_full_cycles;
  longint unsigned rob_head_blocked_cycles;
  longint unsigned rob_occupancy_sum;
  longint unsigned integer_issue_occupancy_sum;
  longint unsigned muldiv_issue_occupancy_sum;
  longint unsigned memory_issue_occupancy_sum;
  longint unsigned store_buffer_occupancy_sum;
  longint unsigned integer_issue_instructions;
  longint unsigned muldiv_issue_instructions;
  longint unsigned memory_issue_instructions;
  longint unsigned integer_issue_blocked_cycles;
  longint unsigned muldiv_issue_blocked_cycles;
  longint unsigned memory_issue_blocked_cycles;
  longint unsigned memory_operand_wait_cycles;
  longint unsigned memory_lsu_wait_cycles;
  longint unsigned memory_recovery_wait_cycles;
  longint unsigned integer_issue_full_cycles;
  longint unsigned muldiv_issue_full_cycles;
  longint unsigned memory_issue_full_cycles;

  longint unsigned speculative_replay_cycles;
  longint unsigned mem2_valid_cycles;
  longint unsigned mem2_cache_wait_cycles;
  longint unsigned mem2_store_buffer_wait_cycles;
  longint unsigned mem2_aux_wait_cycles;

  longint unsigned icache_requests;
  longint unsigned icache_hits;
  longint unsigned icache_misses;
  longint unsigned icache_miss_cycles;
  longint unsigned dcache_requests;
  longint unsigned dcache_hits;
  longint unsigned dcache_misses;
  longint unsigned dcache_miss_cycles;
  longint unsigned dcache_refill_busy_cycles;
  longint unsigned dcache_post_response_refill_cycles;
  longint unsigned dcache_direct_refills;
  longint unsigned dcache_early_load_responses;
  longint unsigned dcache_dirty_writebacks;
  longint unsigned dcache_dirty_victims;
  longint unsigned dcache_tail_blocked_would_hit_cycles;
  longint unsigned dcache_tail_blocked_same_fill_line_cycles;
  longint unsigned dcache_tail_blocked_new_miss_cycles;
  longint unsigned dcache_tail_blocked_store_cycles;
  longint unsigned dcache_dirty_victim_capture_cycles;
  longint unsigned dcache_dirty_victim_ar_wait_cycles;
  longint unsigned dcache_dirty_victim_response_wait_b_cycles;
  longint unsigned dcache_load_misses;
  longint unsigned dcache_store_misses;
  longint unsigned dcache_load_miss_plus_one;
  longint unsigned dcache_load_miss_minus_one;
  longint unsigned dcache_load_miss_repeat;
  longint unsigned dcache_prefetch_candidates;
  longint unsigned dcache_prefetch_requests;
  longint unsigned dcache_prefetch_l2_hits;
  longint unsigned dcache_prefetch_l2_misses;
  longint unsigned dcache_prefetch_buffer_hits;
  longint unsigned dcache_prefetch_late;
  longint unsigned dcache_prefetch_dropped;
  longint unsigned dcache_prefetch_duplicates;
  longint unsigned dcache_prefetch_page_suppressed;
  longint unsigned dcache_prefetch_cancelled;
  longint unsigned dcache_prefetch_useless;
  longint unsigned l2_instruction_reads;
  longint unsigned l2_instruction_hits;
  longint unsigned l2_instruction_misses;
  longint unsigned l2_instruction_miss_cycles;
  longint unsigned l2_data_reads;
  longint unsigned l2_data_hits;
  longint unsigned l2_data_direct_hits;
  longint unsigned l2_data_misses;
  longint unsigned l2_data_miss_cycles;
  longint unsigned l2_data_writes;
  longint unsigned l2_dirty_writebacks;
  longint unsigned l2_read_wait_cycles_blocked_by_write;
  longint unsigned l2_read_wait_cycles_blocked_by_miss;
  longint unsigned l2_hit_wait_cycles_blocked_by_miss;
  longint unsigned l2_instruction_read_wait_cycles;
  longint unsigned l2_data_read_wait_cycles;
  longint unsigned l2_shadow_model_mismatches;
  longint unsigned l2_data_prefetch_reads;
  longint unsigned l2_data_prefetch_hits;
  longint unsigned l2_data_prefetch_misses;
  longint unsigned l2_data_prefetch_wait_cycles;

  longint unsigned unified_64_instruction_hits;
  longint unsigned unified_64_instruction_misses;
  longint unsigned unified_64_data_hits;
  longint unsigned unified_64_data_misses;
  longint unsigned unified_64_data_evicted_by_instruction;
  longint unsigned unified_64_instruction_evicted_by_data;

  bit current_shadow_valid [0:511][0:1];
  bit [16:0] current_shadow_tag [0:511][0:1];
  bit current_shadow_lru [0:511];
  bit unified_64_valid [0:511][0:1];
  bit [16:0] unified_64_tag [0:511][0:1];
  bit unified_64_instruction [0:511][0:1];
  bit unified_64_lru [0:511];

  longint unsigned resolve_cycle [0:31];
  bit resolve_valid [0:31];

  function automatic longint unsigned popcount3(input [2:0] value);
    begin
      popcount3 = {63'b0, value[0]} + {63'b0, value[1]} + {63'b0, value[2]};
    end
  endfunction

  task automatic write_rtltrace_header;
    reg [511:0] header;
    begin
      // <8sII48x>: magic, version, record size, reserved.
      header = 512'b0;
      header[63:0] = 64'h0032564354524c52; // "RLRTCV2\0" little-endian
      header[95:64] = 32'd2;
      header[127:96] = 32'd64;
      $fwrite(rtltrace_fd, "%u", header);
    end
  endtask

  task automatic write_rtltrace_decode(input integer decode_lane);
    reg [511:0] record;
    reg [10:0] score_bits;
    reg [7:0] flags;
    begin
      record = 512'b0;
      score_bits = h64DecodeScore[decode_lane * 11 +: 11];
      flags = {
        1'b0,
        window_active,
        h64DecodeDirectionBias[decode_lane],
        h64DecodeOverride[decode_lane],
        h64DecodeReliable[decode_lane],
        h64DecodeNeuralTaken[decode_lane],
        h64DecodeFastTaken[decode_lane],
        1'b1
      };
      record[7:0] = RTLTRACE_EVENT_DECODE;
      record[15:8] = flags;
      record[23:16] = {3'b0, h64DecodeRobIndex[decode_lane * 5 +: 5]};
      record[31:24] = {4'b0, h64DecodeToken[decode_lane * 4 +: 4]};
      record[39:32] = {5'b0, h64DecodeEpoch[decode_lane * 3 +: 3]};
      record[47:40] = {6'b0, h64DecodeWay[decode_lane * 2 +: 2]};
      record[63:48] = {{5{score_bits[10]}}, score_bits};
      record[95:64] = h64DecodePc[decode_lane * 32 +: 32];
      record[127:96] = h64DecodePath[decode_lane * 32 +: 32];
      record[191:128] = h64DecodeHistory[decode_lane * 64 +: 64];
      record[255:192] = cycle;
      record[263:256] = {6'b0, h64DecodePhtCounter[decode_lane * 2 +: 2]};
      $fwrite(rtltrace_fd, "%u", record);
      rtltrace_pending_valid[h64DecodeRobIndex[decode_lane * 5 +: 5]] = 1;
      rtltrace_pending_roi[h64DecodeRobIndex[decode_lane * 5 +: 5]] = window_active;
      rtltrace_pending_override[h64DecodeRobIndex[decode_lane * 5 +: 5]] =
        h64DecodeOverride[decode_lane];
    end
  endtask

  task automatic write_rtltrace_retire(input integer retire_lane);
    reg [511:0] record;
    reg [7:0] flags;
    begin
      record = 512'b0;
      flags = {
        1'b0,
        window_active,
        3'b0,
        h64RetireActualTaken[retire_lane],
        h64_retired_neural_correct[retire_lane],
        h64_retired_fast_correct[retire_lane]
      };
      record[7:0] = RTLTRACE_EVENT_RETIRE;
      record[15:8] = flags;
      record[23:16] = {3'b0, ((retireRobIndex + retire_lane[4:0]) & 5'h1f)};
      record[95:64] = retirePc[retire_lane * 32 +: 32];
      record[255:192] = cycle;
      $fwrite(rtltrace_fd, "%u", record);
      rtltrace_pending_valid[(retireRobIndex + retire_lane[4:0]) & 5'h1f] = 0;
    end
  endtask

  task automatic write_rtltrace_squash(input integer rob_slot);
    reg [511:0] record;
    begin
      record = 512'b0;
      record[7:0] = 8'd3;
      record[15:8] = {1'b0, rtltrace_pending_roi[rob_slot],
        rtltrace_pending_override[rob_slot], 5'b0};
      record[23:16] = {3'b0, rob_slot[4:0]};
      record[255:192] = cycle;
      $fwrite(rtltrace_fd, "%u", record);
      rtltrace_pending_valid[rob_slot] = 0;
    end
  endtask

  function automatic bit counter_matches_profile(input integer counter_lane);
    begin
      counter_matches_profile =
        !counter_pc_filter_enabled ||
        (retirePc[counter_lane * 32 +: 32] == counter_pc_filter);
    end
  endfunction

  function automatic bit current_shadow_hit(input bit [31:0] address);
    integer set_index;
    bit [16:0] address_tag;
    begin
      set_index = {23'b0, address[14:6]};
      address_tag = address[31:15];
      current_shadow_hit =
        (current_shadow_valid[set_index][0] &&
          current_shadow_tag[set_index][0] == address_tag) ||
        (current_shadow_valid[set_index][1] &&
          current_shadow_tag[set_index][1] == address_tag);
    end
  endfunction

  task automatic access_current_shadow(input bit [31:0] address);
    integer set_index;
    integer hit_way;
    integer replacement_way;
    bit [16:0] address_tag;
    begin
      set_index = {23'b0, address[14:6]};
      address_tag = address[31:15];
      hit_way = -1;
      if (current_shadow_valid[set_index][0] &&
          current_shadow_tag[set_index][0] == address_tag)
        hit_way = 0;
      else if (current_shadow_valid[set_index][1] &&
               current_shadow_tag[set_index][1] == address_tag)
        hit_way = 1;

      // The current 64 KiB unified L2 allocates and touches both I- and D-side
      // reads and complete-line data writes.
      if (hit_way >= 0) begin
        current_shadow_lru[set_index] = !hit_way[0];
      end else begin
        if (!current_shadow_valid[set_index][0])
          replacement_way = 0;
        else if (!current_shadow_valid[set_index][1])
          replacement_way = 1;
        else
          replacement_way = {31'b0, current_shadow_lru[set_index]};
        current_shadow_valid[set_index][replacement_way] = 1;
        current_shadow_tag[set_index][replacement_way] = address_tag;
        current_shadow_lru[set_index] = !replacement_way[0];
      end
    end
  endtask

  task automatic access_unified_64_shadow(
    input bit [31:0] address,
    input bit instruction,
    input bit count_read
  );
    integer set_index;
    integer hit_way;
    integer replacement_way;
    bit [16:0] address_tag;
    begin
      set_index = {23'b0, address[14:6]};
      address_tag = address[31:15];
      hit_way = -1;
      if (unified_64_valid[set_index][0] &&
          unified_64_tag[set_index][0] == address_tag)
        hit_way = 0;
      else if (unified_64_valid[set_index][1] &&
               unified_64_tag[set_index][1] == address_tag)
        hit_way = 1;

      if (hit_way >= 0) begin
        if (window_active && count_read) begin
          if (instruction)
            unified_64_instruction_hits = unified_64_instruction_hits + 1;
          else
            unified_64_data_hits = unified_64_data_hits + 1;
        end
        if (!instruction)
          unified_64_instruction[set_index][hit_way] = 0;
        unified_64_lru[set_index] = !hit_way[0];
      end else begin
        if (window_active && count_read) begin
          if (instruction)
            unified_64_instruction_misses = unified_64_instruction_misses + 1;
          else
            unified_64_data_misses = unified_64_data_misses + 1;
        end
        if (!unified_64_valid[set_index][0])
          replacement_way = 0;
        else if (!unified_64_valid[set_index][1])
          replacement_way = 1;
        else
          replacement_way = {31'b0, unified_64_lru[set_index]};
        if (window_active && unified_64_valid[set_index][replacement_way]) begin
          if (instruction && !unified_64_instruction[set_index][replacement_way])
            unified_64_data_evicted_by_instruction =
              unified_64_data_evicted_by_instruction + 1;
          else if (!instruction && unified_64_instruction[set_index][replacement_way])
            unified_64_instruction_evicted_by_data =
              unified_64_instruction_evicted_by_data + 1;
        end
        unified_64_valid[set_index][replacement_way] = 1;
        unified_64_tag[set_index][replacement_way] = address_tag;
        unified_64_instruction[set_index][replacement_way] = instruction;
        unified_64_lru[set_index] = !replacement_way[0];
      end
    end
  endtask

  task automatic clear_shadow_state;
    integer set_index;
    integer way;
    begin
      for (set_index = 0; set_index < 512; set_index = set_index + 1) begin
        current_shadow_lru[set_index] = 0;
        unified_64_lru[set_index] = 0;
        for (way = 0; way < 2; way = way + 1) begin
          current_shadow_valid[set_index][way] = 0;
          unified_64_valid[set_index][way] = 0;
          unified_64_instruction[set_index][way] = 0;
        end
      end
    end
  endtask

  task automatic clear_profile;
    begin
      profile_cycles = 0;
      timer_cycles = 0;
      retired_instructions = 0;
      retire_zero_cycles = 0;
      retire_one_cycles = 0;
      retire_two_cycles = 0;
      retire_three_cycles = 0;
      branch_retired_count = 0;
      mispredict_retired_count = 0;
      branch_mispredict_count = 0;
      other_recovery_count = 0;
      h64_late_correction_count = 0;
      h64_evaluated_count = 0;
      h64_reliable_count = 0;
      h64_disagreement_count = 0;
      h64_override_count = 0;
      h64_fast_correct_count = 0;
      h64_neural_correct_count = 0;
      h64_useful_count = 0;
      h64_harmful_count = 0;
      resolved_mispredict_count = 0;
      resolve_to_redirect_cycles = 0;
      resolve_to_redirect_max = 0;
      resolve_missing_count = 0;
      rob_squashed_instructions = 0;
      frontend_empty_cycles = 0;
      dispatch_blocked_cycles = 0;
      rob_empty_cycles = 0;
      rob_full_cycles = 0;
      rob_head_blocked_cycles = 0;
      rob_occupancy_sum = 0;
      integer_issue_occupancy_sum = 0;
      muldiv_issue_occupancy_sum = 0;
      memory_issue_occupancy_sum = 0;
      store_buffer_occupancy_sum = 0;
      integer_issue_instructions = 0;
      muldiv_issue_instructions = 0;
      memory_issue_instructions = 0;
      integer_issue_blocked_cycles = 0;
      muldiv_issue_blocked_cycles = 0;
      memory_issue_blocked_cycles = 0;
      memory_operand_wait_cycles = 0;
      memory_lsu_wait_cycles = 0;
      memory_recovery_wait_cycles = 0;
      integer_issue_full_cycles = 0;
      muldiv_issue_full_cycles = 0;
      memory_issue_full_cycles = 0;
      speculative_replay_cycles = 0;
      mem2_valid_cycles = 0;
      mem2_cache_wait_cycles = 0;
      mem2_store_buffer_wait_cycles = 0;
      mem2_aux_wait_cycles = 0;
      icache_requests = 0;
      icache_hits = 0;
      icache_misses = 0;
      icache_miss_cycles = 0;
      dcache_requests = 0;
      dcache_hits = 0;
      dcache_misses = 0;
      dcache_miss_cycles = 0;
      dcache_refill_busy_cycles = 0;
      dcache_post_response_refill_cycles = 0;
      dcache_direct_refills = 0;
      dcache_early_load_responses = 0;
      dcache_dirty_writebacks = 0;
      dcache_dirty_victims = 0;
      dcache_tail_blocked_would_hit_cycles = 0;
      dcache_tail_blocked_same_fill_line_cycles = 0;
      dcache_tail_blocked_new_miss_cycles = 0;
      dcache_tail_blocked_store_cycles = 0;
      dcache_dirty_victim_capture_cycles = 0;
      dcache_dirty_victim_ar_wait_cycles = 0;
      dcache_dirty_victim_response_wait_b_cycles = 0;
      dcache_load_misses = 0;
      dcache_store_misses = 0;
      dcache_load_miss_plus_one = 0;
      dcache_load_miss_minus_one = 0;
      dcache_load_miss_repeat = 0;
      dcache_prefetch_candidates = 0;
      dcache_prefetch_requests = 0;
      dcache_prefetch_l2_hits = 0;
      dcache_prefetch_l2_misses = 0;
      dcache_prefetch_buffer_hits = 0;
      dcache_prefetch_late = 0;
      dcache_prefetch_dropped = 0;
      dcache_prefetch_duplicates = 0;
      dcache_prefetch_page_suppressed = 0;
      dcache_prefetch_cancelled = 0;
      dcache_prefetch_useless = 0;
      l2_instruction_reads = 0;
      l2_instruction_hits = 0;
      l2_instruction_misses = 0;
      l2_instruction_miss_cycles = 0;
      l2_data_reads = 0;
      l2_data_hits = 0;
      l2_data_direct_hits = 0;
      l2_data_misses = 0;
      l2_data_miss_cycles = 0;
      l2_data_writes = 0;
      l2_dirty_writebacks = 0;
      l2_read_wait_cycles_blocked_by_write = 0;
      l2_read_wait_cycles_blocked_by_miss = 0;
      l2_hit_wait_cycles_blocked_by_miss = 0;
      l2_instruction_read_wait_cycles = 0;
      l2_data_read_wait_cycles = 0;
      l2_shadow_model_mismatches = 0;
      l2_data_prefetch_reads = 0;
      l2_data_prefetch_hits = 0;
      l2_data_prefetch_misses = 0;
      l2_data_prefetch_wait_cycles = 0;
      unified_64_instruction_hits = 0;
      unified_64_instruction_misses = 0;
      unified_64_data_hits = 0;
      unified_64_data_misses = 0;
      unified_64_data_evicted_by_instruction = 0;
      unified_64_instruction_evicted_by_data = 0;
    end
  endtask

  task automatic print_profile;
    begin
      $display(
        "gemmont perf profile counter_samples=%0d windows=%0d cycles=%0d timer_cycles=%0d retired=%0d retire_zero_cycles=%0d retire_one_cycles=%0d retire_two_cycles=%0d retire_three_cycles=%0d branches=%0d mispredicts=%0d branch_mispredicts=%0d other_recoveries=%0d h64_late_corrections=%0d h64_evaluated=%0d h64_reliable=%0d h64_disagreements=%0d h64_overrides=%0d h64_fast_correct=%0d h64_neural_correct=%0d h64_useful=%0d h64_harmful=%0d resolved_mispredicts=%0d resolve_to_redirect_cycles=%0d resolve_to_redirect_max=%0d resolve_missing=%0d rob_squashed=%0d frontend_empty_cycles=%0d dispatch_blocked_cycles=%0d rob_empty_cycles=%0d rob_full_cycles=%0d rob_head_blocked_cycles=%0d rob_occupancy_sum=%0d integer_iq_occupancy_sum=%0d muldiv_iq_occupancy_sum=%0d memory_iq_occupancy_sum=%0d store_buffer_occupancy_sum=%0d integer_issued=%0d muldiv_issued=%0d memory_issued=%0d integer_issue_blocked_cycles=%0d muldiv_issue_blocked_cycles=%0d memory_issue_blocked_cycles=%0d memory_operand_wait_cycles=%0d memory_lsu_wait_cycles=%0d memory_recovery_wait_cycles=%0d integer_iq_full_cycles=%0d muldiv_iq_full_cycles=%0d memory_iq_full_cycles=%0d speculative_replay_cycles=%0d mem2_valid_cycles=%0d mem2_cache_wait_cycles=%0d mem2_store_buffer_wait_cycles=%0d mem2_aux_wait_cycles=%0d icache_requests=%0d icache_hits=%0d icache_misses=%0d icache_miss_cycles=%0d dcache_requests=%0d dcache_hits=%0d dcache_misses=%0d dcache_miss_cycles=%0d dcache_refill_busy_cycles=%0d dcache_post_response_refill_cycles=%0d dcache_direct_refills=%0d dcache_early_load_responses=%0d dcache_dirty_writebacks=%0d l2_instruction_reads=%0d l2_instruction_hits=%0d l2_instruction_misses=%0d l2_instruction_miss_cycles=%0d l2_data_reads=%0d l2_data_hits=%0d l2_data_direct_hits=%0d l2_data_misses=%0d l2_data_miss_cycles=%0d l2_data_writes=%0d l2_dirty_writebacks=%0d dcache_tail_blocked_would_hit_cycles=%0d dcache_tail_blocked_same_fill_line_cycles=%0d dcache_tail_blocked_new_miss_cycles=%0d dcache_tail_blocked_store_cycles=%0d dcache_dirty_victim_capture_cycles=%0d dcache_dirty_victim_ar_wait_cycles=%0d dcache_dirty_victim_response_wait_b_cycles=%0d l2_read_wait_cycles_blocked_by_write=%0d l2_read_wait_cycles_blocked_by_miss=%0d l2_hit_wait_cycles_blocked_by_miss=%0d l2_instruction_read_wait_cycles=%0d l2_data_read_wait_cycles=%0d l2_shadow_model_mismatches=%0d unified_64_instruction_hits=%0d unified_64_instruction_misses=%0d unified_64_data_hits=%0d unified_64_data_misses=%0d unified_64_data_evicted_by_instruction=%0d unified_64_instruction_evicted_by_data=%0d dcache_dirty_victims=%0d",
        counter_samples,
        windows,
        profile_cycles,
        timer_cycles,
        retired_instructions,
        retire_zero_cycles,
        retire_one_cycles,
        retire_two_cycles,
        retire_three_cycles,
        branch_retired_count,
        mispredict_retired_count,
        branch_mispredict_count,
        other_recovery_count,
        h64_late_correction_count,
        h64_evaluated_count,
        h64_reliable_count,
        h64_disagreement_count,
        h64_override_count,
        h64_fast_correct_count,
        h64_neural_correct_count,
        h64_useful_count,
        h64_harmful_count,
        resolved_mispredict_count,
        resolve_to_redirect_cycles,
        resolve_to_redirect_max,
        resolve_missing_count,
        rob_squashed_instructions,
        frontend_empty_cycles,
        dispatch_blocked_cycles,
        rob_empty_cycles,
        rob_full_cycles,
        rob_head_blocked_cycles,
        rob_occupancy_sum,
        integer_issue_occupancy_sum,
        muldiv_issue_occupancy_sum,
        memory_issue_occupancy_sum,
        store_buffer_occupancy_sum,
        integer_issue_instructions,
        muldiv_issue_instructions,
        memory_issue_instructions,
        integer_issue_blocked_cycles,
        muldiv_issue_blocked_cycles,
        memory_issue_blocked_cycles,
        memory_operand_wait_cycles,
        memory_lsu_wait_cycles,
        memory_recovery_wait_cycles,
        integer_issue_full_cycles,
        muldiv_issue_full_cycles,
        memory_issue_full_cycles,
        speculative_replay_cycles,
        mem2_valid_cycles,
        mem2_cache_wait_cycles,
        mem2_store_buffer_wait_cycles,
        mem2_aux_wait_cycles,
        icache_requests,
        icache_hits,
        icache_misses,
        icache_miss_cycles,
        dcache_requests,
        dcache_hits,
        dcache_misses,
        dcache_miss_cycles,
        dcache_refill_busy_cycles,
        dcache_post_response_refill_cycles,
        dcache_direct_refills,
        dcache_early_load_responses,
        dcache_dirty_writebacks,
        l2_instruction_reads,
        l2_instruction_hits,
        l2_instruction_misses,
        l2_instruction_miss_cycles,
        l2_data_reads,
        l2_data_hits,
        l2_data_direct_hits,
        l2_data_misses,
        l2_data_miss_cycles,
        l2_data_writes,
        l2_dirty_writebacks,
        dcache_tail_blocked_would_hit_cycles,
        dcache_tail_blocked_same_fill_line_cycles,
        dcache_tail_blocked_new_miss_cycles,
        dcache_tail_blocked_store_cycles,
        dcache_dirty_victim_capture_cycles,
        dcache_dirty_victim_ar_wait_cycles,
        dcache_dirty_victim_response_wait_b_cycles,
        l2_read_wait_cycles_blocked_by_write,
        l2_read_wait_cycles_blocked_by_miss,
        l2_hit_wait_cycles_blocked_by_miss,
        l2_instruction_read_wait_cycles,
        l2_data_read_wait_cycles,
        l2_shadow_model_mismatches,
        unified_64_instruction_hits,
        unified_64_instruction_misses,
        unified_64_data_hits,
        unified_64_data_misses,
        unified_64_data_evicted_by_instruction,
        unified_64_instruction_evicted_by_data,
        dcache_dirty_victims
      );
      $display(
        "gemmont prefetch profile dcache_load_misses=%0d dcache_store_misses=%0d dcache_load_miss_plus_one=%0d dcache_load_miss_minus_one=%0d dcache_load_miss_repeat=%0d candidates=%0d requests=%0d l2_hits=%0d l2_misses=%0d buffer_hits=%0d late=%0d dropped=%0d duplicates=%0d page_suppressed=%0d cancelled=%0d useless=%0d l2_probe_reads=%0d l2_probe_hits=%0d l2_probe_misses=%0d l2_probe_wait_cycles=%0d",
        dcache_load_misses,
        dcache_store_misses,
        dcache_load_miss_plus_one,
        dcache_load_miss_minus_one,
        dcache_load_miss_repeat,
        dcache_prefetch_candidates,
        dcache_prefetch_requests,
        dcache_prefetch_l2_hits,
        dcache_prefetch_l2_misses,
        dcache_prefetch_buffer_hits,
        dcache_prefetch_late,
        dcache_prefetch_dropped,
        dcache_prefetch_duplicates,
        dcache_prefetch_page_suppressed,
        dcache_prefetch_cancelled,
        dcache_prefetch_useless,
        l2_data_prefetch_reads,
        l2_data_prefetch_hits,
        l2_data_prefetch_misses,
        l2_data_prefetch_wait_cycles
      );
      $fflush();
    end
  endtask

  task automatic print_pc_histograms;
    begin
      if (pc_histogram_enabled) begin
        for (pc_histogram_index = 0;
             pc_histogram_index < 65536;
             pc_histogram_index = pc_histogram_index + 1) begin
          if (retired_pc_histogram[pc_histogram_index] != 0)
            $display(
              "gemmont perf pc_histogram pc=%08x retired=%0d",
              32'h1c00_0000 + {14'b0, pc_histogram_index[15:0], 2'b0},
              retired_pc_histogram[pc_histogram_index]
            );
          if (rob_head_pc_histogram[pc_histogram_index] != 0)
            $display(
              "gemmont perf rob_head_pc_histogram pc=%08x cycles=%0d",
              32'h1c00_0000 + {14'b0, pc_histogram_index[15:0], 2'b0},
              rob_head_pc_histogram[pc_histogram_index]
            );
          if (head_stall_pc_histogram[pc_histogram_index] != 0)
            $display(
              "gemmont perf head_stall_pc_histogram pc=%08x cycles=%0d",
              32'h1c00_0000 + {14'b0, pc_histogram_index[15:0], 2'b0},
              head_stall_pc_histogram[pc_histogram_index]
            );
          if (mispredict_pc_histogram[pc_histogram_index] != 0)
            $display(
              "gemmont perf mispredict_pc_histogram pc=%08x mispredicts=%0d",
              32'h1c00_0000 + {14'b0, pc_histogram_index[15:0], 2'b0},
              mispredict_pc_histogram[pc_histogram_index]
            );
          if (icache_wait_pc_histogram[pc_histogram_index] != 0)
            $display(
              "gemmont perf icache_wait_pc_histogram pc=%08x cycles=%0d",
              32'h1c00_0000 + {14'b0, pc_histogram_index[15:0], 2'b0},
              icache_wait_pc_histogram[pc_histogram_index]
            );
          if (dcache_wait_pc_histogram[pc_histogram_index] != 0)
            $display(
              "gemmont perf dcache_wait_pc_histogram pc=%08x cycles=%0d",
              32'h1c00_0000 + {14'b0, pc_histogram_index[15:0], 2'b0},
              dcache_wait_pc_histogram[pc_histogram_index]
            );
          if (memory_issue_blocked_pc_histogram[pc_histogram_index] != 0)
            $display(
              "gemmont perf memory_issue_blocked_pc_histogram pc=%08x cycles=%0d",
              32'h1c00_0000 + {14'b0, pc_histogram_index[15:0], 2'b0},
              memory_issue_blocked_pc_histogram[pc_histogram_index]
            );
          if (memory_operand_wait_pc_histogram[pc_histogram_index] != 0)
            $display(
              "gemmont perf memory_operand_wait_pc_histogram pc=%08x cycles=%0d",
              32'h1c00_0000 + {14'b0, pc_histogram_index[15:0], 2'b0},
              memory_operand_wait_pc_histogram[pc_histogram_index]
            );
          if (memory_address_wait_pc_histogram[pc_histogram_index] != 0)
            $display(
              "gemmont perf memory_address_wait_pc_histogram pc=%08x cycles=%0d",
              32'h1c00_0000 + {14'b0, pc_histogram_index[15:0], 2'b0},
              memory_address_wait_pc_histogram[pc_histogram_index]
            );
          if (memory_data_wait_pc_histogram[pc_histogram_index] != 0)
            $display(
              "gemmont perf memory_data_wait_pc_histogram pc=%08x cycles=%0d",
              32'h1c00_0000 + {14'b0, pc_histogram_index[15:0], 2'b0},
              memory_data_wait_pc_histogram[pc_histogram_index]
            );
          if (memory_lsu_wait_pc_histogram[pc_histogram_index] != 0)
            $display(
              "gemmont perf memory_lsu_wait_pc_histogram pc=%08x cycles=%0d",
              32'h1c00_0000 + {14'b0, pc_histogram_index[15:0], 2'b0},
              memory_lsu_wait_pc_histogram[pc_histogram_index]
            );
          if (memory_recovery_wait_pc_histogram[pc_histogram_index] != 0)
            $display(
              "gemmont perf memory_recovery_wait_pc_histogram pc=%08x cycles=%0d",
              32'h1c00_0000 + {14'b0, pc_histogram_index[15:0], 2'b0},
              memory_recovery_wait_pc_histogram[pc_histogram_index]
            );
        end
      end
      $fflush();
    end
  endtask

  initial begin
    rtltrace_fd = 0;
    rtltrace_path = 0;
    rtltrace_enabled =
      $value$plusargs("gemmont-rtltrace=%s", rtltrace_path);
    if (rtltrace_enabled) begin
      rtltrace_fd = $fopen(rtltrace_path, "wb");
      if (rtltrace_fd == 0)
        $fatal(1, "cannot open rtltrace-v2 output: %0s", rtltrace_path);
      write_rtltrace_header();
    end
    heartbeat_interval = 10000000;
    void'($value$plusargs("gemmont-profile-heartbeat=%d", heartbeat_interval));
    counter_pc_filter = 0;
    counter_pc_filter_enabled =
      $value$plusargs("gemmont-profile-counter-pc=%h", counter_pc_filter);
    counter_start_sample = 0;
    counter_stop_sample = 0;
    counter_start_value = 0;
    counter_sample_range_enabled =
      $value$plusargs(
        "gemmont-profile-counter-start-sample=%d",
        counter_start_sample
      );
    counter_sample_range_enabled =
      $value$plusargs(
        "gemmont-profile-counter-stop-sample=%d",
        counter_stop_sample
      ) || counter_sample_range_enabled;
    if (counter_sample_range_enabled &&
        (counter_start_sample == 0 ||
         counter_stop_sample <= counter_start_sample))
      $fatal(
        1,
        "gemmont profile counter sample range must satisfy 0 < start < stop"
      );
    pc_histogram_enabled = $test$plusargs("gemmont-profile-pc-histogram");
    profile_final_only = $test$plusargs("gemmont-profile-final-only");
    if (pc_histogram_enabled) begin
      for (pc_histogram_index = 0;
           pc_histogram_index < 65536;
           pc_histogram_index = pc_histogram_index + 1) begin
        retired_pc_histogram[pc_histogram_index] = 0;
        rob_head_pc_histogram[pc_histogram_index] = 0;
        head_stall_pc_histogram[pc_histogram_index] = 0;
        mispredict_pc_histogram[pc_histogram_index] = 0;
        icache_wait_pc_histogram[pc_histogram_index] = 0;
        dcache_wait_pc_histogram[pc_histogram_index] = 0;
        memory_issue_blocked_pc_histogram[pc_histogram_index] = 0;
        memory_operand_wait_pc_histogram[pc_histogram_index] = 0;
        memory_address_wait_pc_histogram[pc_histogram_index] = 0;
        memory_data_wait_pc_histogram[pc_histogram_index] = 0;
        memory_lsu_wait_pc_histogram[pc_histogram_index] = 0;
        memory_recovery_wait_pc_histogram[pc_histogram_index] = 0;
      end
    end
    next_heartbeat = heartbeat_interval;
    global_retired = 0;
    counter_samples = 0;
    windows = 0;
    window_active = 0;
    clear_profile();
    clear_shadow_state();
    for (lane = 0; lane < 32; lane = lane + 1)
      resolve_valid[lane] = 0;
    for (lane = 0; lane < 32; lane = lane + 1) begin
      rtltrace_pending_valid[lane] = 0;
      rtltrace_pending_roi[lane] = 0;
      rtltrace_pending_override[lane] = 0;
      h64_pending_valid[lane] = 0;
      h64_pending_fast_taken[lane] = 0;
      h64_pending_neural_taken[lane] = 0;
      h64_pending_reliable[lane] = 0;
      h64_pending_override[lane] = 0;
    end
  end

  final begin
    $display(
      "gemmont perf final cycle=%0d retired=%0d counter_samples=%0d windows=%0d window_active=%0d fetch_pc=%08x retire_pc=%08x rob=%0d mem2_valid=%0d mem2_cache_wait=%0d",
      cycle,
      global_retired,
      counter_samples,
      windows,
      window_active,
      fetchPc,
      retirePc[31:0],
      robOccupancy,
      mem2Valid,
      mem2CacheWait
    );
    if (profile_final_only && windows > 0 && !window_active)
      print_profile();
    print_pc_histograms();
    if (rtltrace_fd != 0)
      $fclose(rtltrace_fd);
  end

  always @(posedge clock) begin
    if (reset) begin
      global_retired = 0;
      counter_samples = 0;
      windows = 0;
      window_active = 0;
      next_heartbeat = heartbeat_interval;
      clear_profile();
      clear_shadow_state();
      if (pc_histogram_enabled) begin
        for (pc_histogram_index = 0;
             pc_histogram_index < 65536;
             pc_histogram_index = pc_histogram_index + 1) begin
          retired_pc_histogram[pc_histogram_index] = 0;
          rob_head_pc_histogram[pc_histogram_index] = 0;
          head_stall_pc_histogram[pc_histogram_index] = 0;
          mispredict_pc_histogram[pc_histogram_index] = 0;
          icache_wait_pc_histogram[pc_histogram_index] = 0;
          dcache_wait_pc_histogram[pc_histogram_index] = 0;
          memory_issue_blocked_pc_histogram[pc_histogram_index] = 0;
          memory_operand_wait_pc_histogram[pc_histogram_index] = 0;
          memory_address_wait_pc_histogram[pc_histogram_index] = 0;
          memory_data_wait_pc_histogram[pc_histogram_index] = 0;
          memory_lsu_wait_pc_histogram[pc_histogram_index] = 0;
          memory_recovery_wait_pc_histogram[pc_histogram_index] = 0;
        end
      end
      for (lane = 0; lane < 32; lane = lane + 1)
        resolve_valid[lane] = 0;
      for (lane = 0; lane < 32; lane = lane + 1) begin
        rtltrace_pending_valid[lane] = 0;
        rtltrace_pending_roi[lane] = 0;
        rtltrace_pending_override[lane] = 0;
        h64_pending_valid[lane] = 0;
        h64_pending_fast_taken[lane] = 0;
        h64_pending_neural_taken[lane] = 0;
        h64_pending_reliable[lane] = 0;
        h64_pending_override[lane] = 0;
      end
    end else begin
      retired_this_cycle = popcount3(retireValid);
      global_retired = global_retired + retired_this_cycle;

      // The production corrector does not carry observation bits through the
      // synthesized ROB. Bind them to the allocated ROB slots in this
      // simulation-only sink and reconstruct retirement metrics here. Process
      // retirement before Rename because a full ROB may reuse one slot on the
      // same edge.
      h64_retired_evaluated = 0;
      h64_retired_reliable = 0;
      h64_retired_disagreement = 0;
      h64_retired_override = 0;
      h64_retired_fast_correct = 0;
      h64_retired_neural_correct = 0;
      for (lane = 0; lane < 3; lane = lane + 1) begin
        if (retireValid[lane]) begin
          h64_retired_evaluated[lane] =
            h64_pending_valid[(retireRobIndex + lane[4:0]) & 5'h1f];
          h64_retired_reliable[lane] = h64_retired_evaluated[lane] &&
            h64_pending_reliable[(retireRobIndex + lane[4:0]) & 5'h1f];
          h64_retired_disagreement[lane] = h64_retired_reliable[lane] &&
            (h64_pending_fast_taken[(retireRobIndex + lane[4:0]) & 5'h1f] !=
             h64_pending_neural_taken[(retireRobIndex + lane[4:0]) & 5'h1f]);
          h64_retired_override[lane] = h64_retired_evaluated[lane] &&
            h64_pending_override[(retireRobIndex + lane[4:0]) & 5'h1f];
          h64_retired_fast_correct[lane] = h64_retired_evaluated[lane] &&
            (h64_pending_fast_taken[(retireRobIndex + lane[4:0]) & 5'h1f] ==
             h64RetireActualTaken[lane]);
          h64_retired_neural_correct[lane] = h64_retired_evaluated[lane] &&
            (h64_pending_neural_taken[(retireRobIndex + lane[4:0]) & 5'h1f] ==
             h64RetireActualTaken[lane]);
          h64_pending_valid[(retireRobIndex + lane[4:0]) & 5'h1f] = 0;
        end
      end
      for (lane = 0; lane < 3; lane = lane + 1) begin
        if (h64RenameFire[lane]) begin
          // Every allocation overwrites the slot, including ordinary
          // instructions. This prevents metadata from a squashed H64 branch
          // surviving until the same ROB index is reused by a non-H64 entry.
          h64_pending_valid[h64RenameRobIndex[lane * 5 +: 5]] =
            h64RenameEvaluated[lane];
          h64_pending_fast_taken[h64RenameRobIndex[lane * 5 +: 5]] =
            h64RenameFastTaken[lane];
          h64_pending_neural_taken[h64RenameRobIndex[lane * 5 +: 5]] =
            h64RenameNeuralTaken[lane];
          h64_pending_reliable[h64RenameRobIndex[lane * 5 +: 5]] =
            h64RenameReliable[lane];
          h64_pending_override[h64RenameRobIndex[lane * 5 +: 5]] =
            h64RenameOverride[lane];
        end
      end

      // Resolution timestamps are retained outside the ROI so a branch which
      // resolves just before the opening CNT can still be matched at retirement.
      for (lane = 0; lane < 3; lane = lane + 1) begin
        if (mispredictResolved[lane]) begin
          resolve_cycle[mispredictResolvedRob[lane * 5 +: 5]] = cycle;
          resolve_valid[mispredictResolvedRob[lane * 5 +: 5]] = 1;
        end
      end

      if (window_active) begin
        profile_cycles = profile_cycles + 1;
        retired_instructions = retired_instructions + retired_this_cycle;
        case (retired_this_cycle)
          0: retire_zero_cycles = retire_zero_cycles + 1;
          1: retire_one_cycles = retire_one_cycles + 1;
          2: retire_two_cycles = retire_two_cycles + 1;
          3: retire_three_cycles = retire_three_cycles + 1;
        endcase
        if (pc_histogram_enabled) begin
          for (lane = 0; lane < 3; lane = lane + 1) begin
            if (retireValid[lane] &&
                retirePc[lane * 32 +: 32] >= 32'h1c00_0000 &&
                retirePc[lane * 32 +: 32] < 32'h1c04_0000)
              retired_pc_histogram[
                (retirePc[lane * 32 +: 32] - 32'h1c00_0000) >> 2
              ] = retired_pc_histogram[
                (retirePc[lane * 32 +: 32] - 32'h1c00_0000) >> 2
              ] + 1;
          end
          if (robOccupancy != 0 &&
              retirePc[31:0] >= 32'h1c00_0000 &&
              retirePc[31:0] < 32'h1c04_0000)
            rob_head_pc_histogram[
              (retirePc[31:0] - 32'h1c00_0000) >> 2
            ] = rob_head_pc_histogram[
              (retirePc[31:0] - 32'h1c00_0000) >> 2
            ] + 1;
          if (retired_this_cycle == 0 && robOccupancy != 0 &&
              retirePc[31:0] >= 32'h1c00_0000 &&
              retirePc[31:0] < 32'h1c04_0000)
            head_stall_pc_histogram[
              (retirePc[31:0] - 32'h1c00_0000) >> 2
            ] = head_stall_pc_histogram[
              (retirePc[31:0] - 32'h1c00_0000) >> 2
            ] + 1;
          if (mispredictRetired &&
              retirePc[31:0] >= 32'h1c00_0000 &&
              retirePc[31:0] < 32'h1c04_0000)
            mispredict_pc_histogram[
              (retirePc[31:0] - 32'h1c00_0000) >> 2
            ] = mispredict_pc_histogram[
              (retirePc[31:0] - 32'h1c00_0000) >> 2
              ] + 1;
          if (instructionCacheMissBusy &&
              fetchPc >= 32'h1c00_0000 && fetchPc < 32'h1c04_0000)
            icache_wait_pc_histogram[
              (fetchPc - 32'h1c00_0000) >> 2
            ] = icache_wait_pc_histogram[
              (fetchPc - 32'h1c00_0000) >> 2
            ] + 1;
          if (mem2Valid && mem2CacheWait &&
              mem2Pc >= 32'h1c00_0000 && mem2Pc < 32'h1c04_0000)
            dcache_wait_pc_histogram[
              (mem2Pc - 32'h1c00_0000) >> 2
            ] = dcache_wait_pc_histogram[
              (mem2Pc - 32'h1c00_0000) >> 2
            ] + 1;
          if (memoryIssueOccupancy != 0 && !memoryIssue &&
              memoryIssueHeadPc >= 32'h1c00_0000 &&
              memoryIssueHeadPc < 32'h1c04_0000)
            memory_issue_blocked_pc_histogram[
              (memoryIssueHeadPc - 32'h1c00_0000) >> 2
            ] = memory_issue_blocked_pc_histogram[
              (memoryIssueHeadPc - 32'h1c00_0000) >> 2
            ] + 1;
          if (memoryIssueOccupancy != 0 && !mispredictRetired && !otherRecovery &&
              !memoryIssueOperandsReady &&
              memoryIssueHeadPc >= 32'h1c00_0000 &&
              memoryIssueHeadPc < 32'h1c04_0000)
            memory_operand_wait_pc_histogram[
              (memoryIssueHeadPc - 32'h1c00_0000) >> 2
            ] = memory_operand_wait_pc_histogram[
              (memoryIssueHeadPc - 32'h1c00_0000) >> 2
            ] + 1;
          if (memoryIssueOccupancy != 0 && !mispredictRetired && !otherRecovery &&
              !memoryIssueAddressReady &&
              memoryIssueHeadPc >= 32'h1c00_0000 &&
              memoryIssueHeadPc < 32'h1c04_0000)
            memory_address_wait_pc_histogram[
              (memoryIssueHeadPc - 32'h1c00_0000) >> 2
            ] = memory_address_wait_pc_histogram[
              (memoryIssueHeadPc - 32'h1c00_0000) >> 2
            ] + 1;
          if (memoryIssueOccupancy != 0 && !mispredictRetired && !otherRecovery &&
              memoryIssueAddressReady && !memoryIssueDataReady &&
              memoryIssueHeadPc >= 32'h1c00_0000 &&
              memoryIssueHeadPc < 32'h1c04_0000)
            memory_data_wait_pc_histogram[
              (memoryIssueHeadPc - 32'h1c00_0000) >> 2
            ] = memory_data_wait_pc_histogram[
              (memoryIssueHeadPc - 32'h1c00_0000) >> 2
            ] + 1;
          if (memoryIssueOccupancy != 0 && !mispredictRetired && !otherRecovery &&
              memoryIssueOperandsReady && !memoryIssueLsuReady &&
              memoryIssueHeadPc >= 32'h1c00_0000 &&
              memoryIssueHeadPc < 32'h1c04_0000)
            memory_lsu_wait_pc_histogram[
              (memoryIssueHeadPc - 32'h1c00_0000) >> 2
            ] = memory_lsu_wait_pc_histogram[
              (memoryIssueHeadPc - 32'h1c00_0000) >> 2
            ] + 1;
          if (memoryIssueOccupancy != 0 &&
              (mispredictRetired || otherRecovery) &&
              memoryIssueHeadPc >= 32'h1c00_0000 &&
              memoryIssueHeadPc < 32'h1c04_0000)
            memory_recovery_wait_pc_histogram[
              (memoryIssueHeadPc - 32'h1c00_0000) >> 2
            ] = memory_recovery_wait_pc_histogram[
              (memoryIssueHeadPc - 32'h1c00_0000) >> 2
            ] + 1;
        end

        branch_retired_count = branch_retired_count + branchRetired;
        mispredict_retired_count = mispredict_retired_count + mispredictRetired;
        branch_mispredict_count = branch_mispredict_count + branchMispredictRetired;
        other_recovery_count = other_recovery_count + otherRecovery;
        h64_late_correction_count = h64_late_correction_count + h64LateCorrection;
        h64_evaluated_count = h64_evaluated_count + popcount3(h64_retired_evaluated);
        h64_reliable_count = h64_reliable_count + popcount3(h64_retired_reliable);
        h64_disagreement_count = h64_disagreement_count + popcount3(h64_retired_disagreement);
        h64_override_count = h64_override_count + popcount3(h64_retired_override);
        h64_fast_correct_count = h64_fast_correct_count + popcount3(h64_retired_fast_correct);
        h64_neural_correct_count = h64_neural_correct_count + popcount3(h64_retired_neural_correct);
        h64_useful_count = h64_useful_count + popcount3(
          h64_retired_evaluated & h64_retired_reliable &
          ~h64_retired_fast_correct & h64_retired_neural_correct
        );
        h64_harmful_count = h64_harmful_count + popcount3(
          h64_retired_evaluated & h64_retired_reliable &
          h64_retired_fast_correct & ~h64_retired_neural_correct
        );
        if (mispredictRetired) begin
          if (robOccupancy > 0)
            rob_squashed_instructions =
              rob_squashed_instructions + {58'b0, robOccupancy} - 1;
          if (resolve_valid[retireRobIndex]) begin
            resolved_mispredict_count = resolved_mispredict_count + 1;
            resolve_to_redirect_cycles = resolve_to_redirect_cycles + cycle - resolve_cycle[retireRobIndex];
            if (cycle - resolve_cycle[retireRobIndex] > resolve_to_redirect_max)
              resolve_to_redirect_max = cycle - resolve_cycle[retireRobIndex];
          end else begin
            resolve_missing_count = resolve_missing_count + 1;
          end
        end

        frontend_empty_cycles = frontend_empty_cycles + (frontendValid == 0);
        dispatch_blocked_cycles = dispatch_blocked_cycles + dispatchBlocked;
        rob_empty_cycles = rob_empty_cycles + (robOccupancy == 0);
        rob_full_cycles = rob_full_cycles + (robOccupancy == 32);
        rob_head_blocked_cycles = rob_head_blocked_cycles +
          ((robOccupancy != 0) && (retired_this_cycle == 0));
        rob_occupancy_sum = rob_occupancy_sum + {58'b0, robOccupancy};
        integer_issue_occupancy_sum =
          integer_issue_occupancy_sum + {61'b0, integerIssueOccupancy};
        muldiv_issue_occupancy_sum =
          muldiv_issue_occupancy_sum + {62'b0, mulDivIssueOccupancy};
        memory_issue_occupancy_sum =
          memory_issue_occupancy_sum + {61'b0, memoryIssueOccupancy};
        store_buffer_occupancy_sum =
          store_buffer_occupancy_sum + {60'b0, storeBufferOccupancy};

        issued_this_cycle = popcount3(integerIssue);
        integer_issue_instructions = integer_issue_instructions + issued_this_cycle;
        muldiv_issue_instructions = muldiv_issue_instructions + mulDivIssue;
        memory_issue_instructions = memory_issue_instructions + memoryIssue;
        integer_issue_blocked_cycles = integer_issue_blocked_cycles +
          ((integerIssueOccupancy != 0) && (issued_this_cycle == 0));
        muldiv_issue_blocked_cycles = muldiv_issue_blocked_cycles +
          ((mulDivIssueOccupancy != 0) && !mulDivIssue);
        memory_issue_blocked_cycles = memory_issue_blocked_cycles +
          ((memoryIssueOccupancy != 0) && !memoryIssue);
        memory_operand_wait_cycles = memory_operand_wait_cycles +
          ((memoryIssueOccupancy != 0) && !mispredictRetired && !otherRecovery &&
           !memoryIssueOperandsReady);
        memory_lsu_wait_cycles = memory_lsu_wait_cycles +
          ((memoryIssueOccupancy != 0) && !mispredictRetired && !otherRecovery &&
           memoryIssueOperandsReady &&
           !memoryIssueLsuReady);
        memory_recovery_wait_cycles = memory_recovery_wait_cycles +
          ((memoryIssueOccupancy != 0) && (mispredictRetired || otherRecovery));
        integer_issue_full_cycles = integer_issue_full_cycles + (integerIssueOccupancy == 7);
        muldiv_issue_full_cycles = muldiv_issue_full_cycles + (mulDivIssueOccupancy == 3);
        memory_issue_full_cycles = memory_issue_full_cycles + (memoryIssueOccupancy == 5);

        speculative_replay_cycles = speculative_replay_cycles + speculativeWakeupFailed;
        mem2_valid_cycles = mem2_valid_cycles + mem2Valid;
        mem2_cache_wait_cycles = mem2_cache_wait_cycles + (mem2Valid && mem2CacheWait);
        mem2_store_buffer_wait_cycles = mem2_store_buffer_wait_cycles +
          (mem2Valid && mem2StoreBufferWait);
        mem2_aux_wait_cycles = mem2_aux_wait_cycles + (mem2Valid && mem2AuxWait);

        icache_requests = icache_requests + instructionCacheRequest;
        icache_hits = icache_hits + instructionCacheHit;
        icache_misses = icache_misses + instructionCacheMiss;
        icache_miss_cycles = icache_miss_cycles + instructionCacheMissBusy;
        dcache_requests = dcache_requests + dataCacheRequest;
        dcache_hits = dcache_hits + dataCacheHit;
        dcache_misses = dcache_misses + dataCacheMiss;
        dcache_miss_cycles = dcache_miss_cycles + dataCacheMissBusy;
        dcache_refill_busy_cycles = dcache_refill_busy_cycles + dataCacheRefillBusy;
        dcache_post_response_refill_cycles = dcache_post_response_refill_cycles +
          dataCachePostResponseRefillBusy;
        dcache_direct_refills = dcache_direct_refills + dataCacheDirectRefill;
        dcache_early_load_responses = dcache_early_load_responses + dataCacheEarlyResponse;
        dcache_dirty_writebacks = dcache_dirty_writebacks + dataCacheDirtyWriteback;
        dcache_dirty_victims = dcache_dirty_victims + dataCacheDirtyVictim;
        dcache_tail_blocked_would_hit_cycles =
          dcache_tail_blocked_would_hit_cycles + dataCacheTailBlockedWouldHit;
        dcache_tail_blocked_same_fill_line_cycles =
          dcache_tail_blocked_same_fill_line_cycles + dataCacheTailBlockedSameFillLine;
        dcache_tail_blocked_new_miss_cycles =
          dcache_tail_blocked_new_miss_cycles + dataCacheTailBlockedNewMiss;
        dcache_tail_blocked_store_cycles =
          dcache_tail_blocked_store_cycles + dataCacheTailBlockedStore;
        dcache_dirty_victim_capture_cycles =
          dcache_dirty_victim_capture_cycles + dataCacheDirtyVictimCaptureBusy;
        dcache_dirty_victim_ar_wait_cycles =
          dcache_dirty_victim_ar_wait_cycles + dataCacheDirtyVictimReadAddressWait;
        dcache_dirty_victim_response_wait_b_cycles =
          dcache_dirty_victim_response_wait_b_cycles + dataCacheDirtyVictimResponseWait;
        dcache_load_misses = dcache_load_misses + dataCacheLoadMiss;
        dcache_store_misses = dcache_store_misses + dataCacheStoreMiss;
        dcache_load_miss_plus_one =
          dcache_load_miss_plus_one + dataCacheLoadMissPlusOne;
        dcache_load_miss_minus_one =
          dcache_load_miss_minus_one + dataCacheLoadMissMinusOne;
        dcache_load_miss_repeat =
          dcache_load_miss_repeat + dataCacheLoadMissRepeat;
        dcache_prefetch_candidates =
          dcache_prefetch_candidates + dataCachePrefetchCandidate;
        dcache_prefetch_requests =
          dcache_prefetch_requests + dataCachePrefetchRequest;
        dcache_prefetch_l2_hits =
          dcache_prefetch_l2_hits + dataCachePrefetchL2Hit;
        dcache_prefetch_l2_misses =
          dcache_prefetch_l2_misses + dataCachePrefetchL2Miss;
        dcache_prefetch_buffer_hits =
          dcache_prefetch_buffer_hits + dataCachePrefetchBufferHit;
        dcache_prefetch_late = dcache_prefetch_late + dataCachePrefetchLate;
        dcache_prefetch_dropped =
          dcache_prefetch_dropped + dataCachePrefetchDropped;
        dcache_prefetch_duplicates =
          dcache_prefetch_duplicates + dataCachePrefetchDuplicate;
        dcache_prefetch_page_suppressed =
          dcache_prefetch_page_suppressed + dataCachePrefetchPageSuppressed;
        dcache_prefetch_cancelled =
          dcache_prefetch_cancelled + dataCachePrefetchCancelled;
        dcache_prefetch_useless =
          dcache_prefetch_useless + dataCachePrefetchUseless;
        l2_instruction_reads = l2_instruction_reads + l2InstructionRead;
        l2_instruction_hits = l2_instruction_hits + l2InstructionHit;
        l2_instruction_misses = l2_instruction_misses + l2InstructionMiss;
        l2_instruction_miss_cycles = l2_instruction_miss_cycles + l2InstructionMissBusy;
        l2_data_reads = l2_data_reads + l2DataRead;
        l2_data_hits = l2_data_hits + l2DataHit;
        l2_data_direct_hits = l2_data_direct_hits + l2DataDirectHit;
        l2_data_misses = l2_data_misses + l2DataMiss;
        l2_data_miss_cycles = l2_data_miss_cycles + l2DataMissBusy;
        l2_data_writes = l2_data_writes + l2DataWrite;
        l2_dirty_writebacks = l2_dirty_writebacks + l2DirtyWriteback;
        l2_read_wait_cycles_blocked_by_write =
          l2_read_wait_cycles_blocked_by_write +
          (l2WriteBusy && !l2ReadMissBusy && l2InstructionReadWait) +
          (l2WriteBusy && !l2ReadMissBusy && l2DataReadWait);
        l2_read_wait_cycles_blocked_by_miss =
          l2_read_wait_cycles_blocked_by_miss +
          (l2ReadMissBusy && l2InstructionReadWait) +
          (l2ReadMissBusy && l2DataReadWait);
        l2_hit_wait_cycles_blocked_by_miss =
          l2_hit_wait_cycles_blocked_by_miss +
          (l2ReadMissBusy && l2InstructionReadWait &&
            current_shadow_hit(l2InstructionReadWaitAddress)) +
          (l2ReadMissBusy && l2DataReadWait &&
            current_shadow_hit(l2DataReadWaitAddress));
        l2_instruction_read_wait_cycles =
          l2_instruction_read_wait_cycles + l2InstructionReadWait;
        l2_data_read_wait_cycles = l2_data_read_wait_cycles + l2DataReadWait;
        l2_data_prefetch_reads = l2_data_prefetch_reads + l2DataPrefetchRead;
        l2_data_prefetch_hits = l2_data_prefetch_hits + l2DataPrefetchHit;
        l2_data_prefetch_misses = l2_data_prefetch_misses + l2DataPrefetchMiss;
        l2_data_prefetch_wait_cycles =
          l2_data_prefetch_wait_cycles + l2DataPrefetchWait;
        if (l2InstructionRead &&
            current_shadow_hit(l2ReadAddress) != l2InstructionHit)
          l2_shadow_model_mismatches = l2_shadow_model_mismatches + 1;
        if (l2DataRead && current_shadow_hit(l2ReadAddress) != l2DataHit)
          l2_shadow_model_mismatches = l2_shadow_model_mismatches + 1;
      end

      // Trace the complete execution so an H64 decode just outside the ROI can
      // still be paired with a retirement inside it (and vice versa). Bit six
      // on decode records preserves the current ROI state for scoring.
      if (rtltrace_enabled) begin
        // Retirement must precede allocation in the file. A full ROB may
        // retire and reuse the same slot on one edge.
        for (lane = 0; lane < 3; lane = lane + 1) begin
          if (retireValid[lane] && h64_retired_evaluated[lane])
            write_rtltrace_retire(lane);
        end
      end

      if (rtltrace_enabled && (mispredictRetired || otherRecovery)) begin
        for (lane = 0; lane < 32; lane = lane + 1) begin
          if (rtltrace_pending_valid[lane])
            write_rtltrace_squash(lane);
        end
      end

      if (rtltrace_enabled) begin
        for (lane = 0; lane < 3; lane = lane + 1) begin
          if (h64DecodeFire[lane])
            write_rtltrace_decode(lane);
        end
      end

      // Keep the cache models warm outside the measured CNT window. Unified
      // counters are incremented by the access tasks only while the window is
      // active, but their tags reflect the complete execution since reset.
      if (l2InstructionRead) begin
        access_unified_64_shadow(l2ReadAddress, 1, 1);
        access_current_shadow(l2ReadAddress);
      end
      if (l2DataRead) begin
        access_unified_64_shadow(l2ReadAddress, 0, 1);
        access_current_shadow(l2ReadAddress);
      end
      if (l2DataWrite) begin
        access_unified_64_shadow(l2DataWriteAddress, 0, 0);
        access_current_shadow(l2DataWriteAddress);
      end

      // CNT instructions are unique-retire operations. Counting before this
      // transition excludes the opening CNT and includes the closing CNT,
      // matching Chiplab's existing timer/instruction window convention.
      for (lane = 0; lane < 3; lane = lane + 1) begin
        if (retireValid[lane] && retireCounter[lane] &&
            counter_matches_profile(lane)) begin
          counter_samples = counter_samples + 1;
          if (counter_sample_range_enabled) begin
            if (counter_samples == counter_start_sample) begin
              counter_start_value = retireCounterValue[lane * 32 +: 32];
              window_active = 1;
            end else if (counter_samples == counter_stop_sample) begin
              timer_cycles = timer_cycles + {
                32'b0,
                retireCounterValue[lane * 32 +: 32] - counter_start_value
              };
              window_active = 0;
              windows = windows + 1;
              if (!profile_final_only)
                print_profile();
            end
          end else begin
            if (window_active) begin
              timer_cycles = timer_cycles + {
                32'b0,
                retireCounterValue[lane * 32 +: 32] - counter_start_value
              };
              window_active = 0;
              windows = windows + 1;
              if (!profile_final_only)
                print_profile();
            end else begin
              counter_start_value = retireCounterValue[lane * 32 +: 32];
              window_active = 1;
            end
          end
        end
      end

      for (lane = 0; lane < 3; lane = lane + 1) begin
        if (retireValid[lane])
          resolve_valid[(retireRobIndex + lane[4:0]) & 5'h1f] = 0;
      end

      if (heartbeat_interval > 0 && cycle >= next_heartbeat) begin
        $display(
          "gemmont perf heartbeat cycle=%0d retired=%0d counter_samples=%0d windows=%0d window_active=%0d fetch_pc=%08x retire_pc=%08x rob=%0d int_iq=%0d mdu_iq=%0d mem_iq=%0d mem2_valid=%0d mem2_cache_wait=%0d icache_miss_busy=%0d dcache_miss_busy=%0d",
          cycle,
          global_retired,
          counter_samples,
          windows,
          window_active,
          fetchPc,
          retirePc[31:0],
          robOccupancy,
          integerIssueOccupancy,
          mulDivIssueOccupancy,
          memoryIssueOccupancy,
          mem2Valid,
          mem2CacheWait,
          instructionCacheMissBusy,
          dataCacheMissBusy
        );
        $fflush();
        next_heartbeat = next_heartbeat + heartbeat_interval;
      end
    end
  end
`endif
endmodule
