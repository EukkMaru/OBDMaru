import base64
import json
from pathlib import Path
import tempfile
import unittest
import bottleneck


class BottleneckTests(unittest.TestCase):
    def fixture(self, rid=1, valid=True, warmup=False, duration=40):
        start=1_000_000_000*rid
        def rx(raw, ms):
            return dict(type="rx", request_id=rid, base64=base64.b64encode(raw).decode(),
                        read_end_ns=start+ms*1_000_000, mono_ns=start+(ms+20)*1_000_000)
        return [
            dict(type="tx", request_id=rid, command="ATSH7E0", phase="setup", mono_ns=start-1),
            dict(type="tx", request_id=rid, command="010C0D", phase="bench:pair", mono_ns=start),
            rx(b"010C0D\r7E806410C0C", 10),
            rx(b"800D00\r", 30),
            rx(b">", duration),
            dict(type="request_end", request_id=rid, outcome="response" if valid else "no_data"),
            dict(type="request_timing", request_id=rid, write_start_ns=start, write_end_ns=start+1_000_000,
                 first_read_ns=start+10_000_000, last_read_ns=start+duration*1_000_000,
                 rx_log_ns=500_000, max_delivery_to_dequeue_ns=1_000_000),
            dict(type="benchmark_sample", id="pair", warmup=warmup, valid=valid,
                 command="010C0D", wire_command="010C0D", header_ms=0, query_ms=duration, cycle_ms=duration),
        ]

    def report(self, items):
        return bottleneck.summarize([dict(type="benchmark_phase_start", id="pair", label="pair")]+items)

    def test_fragmented_line_uses_read_delivery_not_log_timestamp(self):
        r=self.report(self.fixture())
        m=r["phases"][0]["valid_metrics"]
        self.assertEqual(m["write_to_first_read_ms"]["median"], 10)
        self.assertEqual(m["write_to_first_can_line_ms"]["median"], 30)
        self.assertEqual(m["last_can_line_to_prompt_ms"]["median"], 10)
        self.assertEqual(m["phone_can_lines"]["median"], 1)
        self.assertEqual(m["stream_chunks"]["median"], 3)
        self.assertEqual(m["rx_log_ms"]["median"], .5)

    def test_warmup_and_failed_samples_do_not_make_valid_latencies_faster(self):
        r=self.report(self.fixture(1, warmup=True, duration=5)+self.fixture(2,valid=False,duration=1)+self.fixture(3,duration=60))
        p=r["phases"][0]
        self.assertEqual((p["attempted_samples"], p["valid_samples"], p["warmup_samples"]), (2,1,1))
        self.assertEqual(p["valid_metrics"]["query_ms"]["median"], 60)
        self.assertEqual(p["outcomes"], {"response":2,"no_data":1})
        self.assertEqual(p["invalid_including_warmup"],1)

    def test_headerless_output_and_hex_echo_are_not_can_frames(self):
        self.assertFalse(bottleneck.frame("2201A46", "7E1"))
        self.assertFalse(bottleneck.frame("410D00", "7E0"))
        self.assertTrue(bottleneck.frame("7E8 03 41 0D 00", "7DF"))
        self.assertFalse(bottleneck.frame("7E9 03 41 0D 00", "7E0"))

    def test_missing_timing_is_explicit_and_incomplete_not_success(self):
        items=[e for e in self.fixture() if e["type"] not in ("request_timing","request_end")]
        r=self.report(items)
        self.assertFalse(r["detailed_transport_timing"])
        self.assertFalse(r["benchmark_complete"])
        self.assertFalse(r["clean_session_end"])
        self.assertEqual(r["phases"][0]["outcomes"], {"incomplete":1})

    def test_aborted_and_skipped_phases_remain_visible_without_samples(self):
        r=bottleneck.summarize([
            dict(type="benchmark_phase_abort",id="timeout",reason="timeout"),
            dict(type="benchmark_phase_skip",id="unsupported",reason="setting rejected"),
            dict(type="request_error",request_id=99,error="EOF"),
        ])
        self.assertEqual(r["request_errors"],1)
        self.assertEqual(r["phases"][0]["aborted"],"timeout")
        self.assertEqual(r["phases"][1]["skipped"],"setting rejected")

    def test_engine_batch_without_lateness_does_not_invent_zero(self):
        r=bottleneck.summarize([
            dict(type="benchmark_phase_start",id="scheduler",label="Scheduler"),
            dict(type="dashboard_poll_timing",channel="engine_batch",query_ms=40,header_ms=20),
            dict(type="dashboard_poll_timing",channel="tcm",query_ms=80,header_ms=15,late_ms=12),
        ])
        self.assertEqual(r["scheduler"]["engine_batch"]["query_ms"]["median"],40)
        self.assertEqual(r["scheduler"]["engine_batch"]["late_ms"]["n"],0)
        self.assertIsNone(r["scheduler"]["engine_batch"]["late_ms"]["median"])
        self.assertEqual(r["scheduler"]["tcm"]["late_ms"]["median"],12)

    def test_sync_cost_completion_and_private_identifiers(self):
        r=self.report(self.fixture()+[
            dict(type="log_sync",sync_start_ns=1,sync_end_ns=3_000_001),
            dict(type="session_start",adapter_address="PRIVATE_MAC",vehicle="PRIVATE_VIN"),
            dict(type="benchmark_complete"),dict(type="session_end")])
        self.assertEqual(r["log_sync_ms"]["median"],3)
        self.assertTrue(r["benchmark_complete"] and r["clean_session_end"])
        self.assertNotIn("PRIVATE",json.dumps(r))

    def test_only_unfinished_last_line_can_be_ignored(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)/"small.jsonl"
            p.write_text('{}\n{"type":')
            self.assertEqual(list(bottleneck.events(p)),[{}])
            p.write_text('broken\n{}\n')
            with self.assertRaises(json.JSONDecodeError):list(bottleneck.events(p))
            p.write_text('{}\nbroken\n')
            with self.assertRaises(json.JSONDecodeError):list(bottleneck.events(p))


if __name__ == "__main__":unittest.main()
