import base64
import io
import json
import tempfile
import unittest
from pathlib import Path
import session

class SessionTests(unittest.TestCase):
    def fixture(self):
        return [
            dict(type="session_start", elapsed_ns=0, demo=True),
            dict(type="tx", elapsed_ns=10, request_id=1, command="0100"),
            dict(type="rx", elapsed_ns=20, request_id=1, base64=base64.b64encode(b"NO DA").decode()),
            dict(type="rx", elapsed_ns=30, request_id=1, base64=base64.b64encode(b"TA\r>").decode()),
            dict(type="request_end", elapsed_ns=31, request_id=1, outcome="no_data"),
        ]
    def test_exact_chunk_replay(self):
        out = io.BytesIO()
        session.replay(self.fixture(), 0, out)
        self.assertEqual(out.getvalue(), b"NO DATA\r>")
    def test_failed_request_is_coverage(self):
        report = session.summarize(self.fixture())
        self.assertEqual(report["coverage_by_command"]["0100"]["no_data"], 1)
        self.assertFalse(report["clean_end"])
    def test_interrupted_tail_is_recoverable(self):
        with tempfile.TemporaryDirectory() as d:
            path = Path(d)/"capture.jsonl"
            path.write_text("\n".join(json.dumps(e) for e in self.fixture()) + '\n{"type":')
            self.assertEqual(session.load(path), self.fixture())
    def test_guide_intentions_and_observations_stay_separate(self):
        events = self.fixture() + [
            dict(type="guide_step", elapsed_ns=40, stage="departure_setup", expected_before="P", expected_after="D; parking brake released", driver_action_confirmed=False),
            dict(type="guide_control_observation", elapsed_ns=50, source="7E1/2201A4", values={"b21": 8}, parking_brake_known=False),
        ]
        report = session.summarize(events)
        self.assertFalse(report["guide_steps"][0]["driver_action_confirmed"])
        self.assertEqual(report["guide_steps"][0]["expected_after"], "D; parking brake released")
        self.assertEqual(report["guide_control_observation_counts"], {"7E1/2201A4": 1})

    def test_interior_corruption_is_not_silently_dropped(self):
        with tempfile.TemporaryDirectory() as d:
            path = Path(d)/"capture.jsonl"
            path.write_text('bad\n{}\n')
            with self.assertRaises(json.JSONDecodeError): session.load(path)

if __name__ == "__main__": unittest.main()
