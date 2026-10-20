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
    def test_interior_corruption_is_not_silently_dropped(self):
        with tempfile.TemporaryDirectory() as d:
            path = Path(d)/"capture.jsonl"
            path.write_text('bad\n{}\n')
            with self.assertRaises(json.JSONDecodeError): session.load(path)

if __name__ == "__main__": unittest.main()
