#!/usr/bin/env python3
import json
import unittest
from agent import parse_tool_call, is_command_safe, execute_shell_command

class TestAgent(unittest.TestCase):

    def test_parse_tool_call_json(self):
        text = '{"tool": "shell", "command": "ls -la"}'
        res = parse_tool_call(text)
        self.assertIsNotNone(res)
        self.assertEqual(res["tool"], "shell")
        self.assertEqual(res["command"], "ls -la")

    def test_parse_tool_call_markdown(self):
        text = 'Here is the command:\n```json\n{"tool": "shell", "command": "git status"}\n```\n'
        res = parse_tool_call(text)
        self.assertIsNotNone(res)
        self.assertEqual(res["tool"], "shell")
        self.assertEqual(res["command"], "git status")

    def test_security_filter(self):
        safe, _ = is_command_safe("ls -la /sdcard")
        self.assertTrue(safe)

        unsafe, reason = is_command_safe("rm -rf /")
        self.assertFalse(unsafe)
        self.assertIn("rejected", reason)

        unsafe_fork, _ = is_command_safe(":(){ :|:& };:")
        self.assertFalse(unsafe_fork)

    def test_execute_shell_command(self):
        rc, out = execute_shell_command("echo 'HELLO_JARVIS_AGENT'")
        self.assertEqual(rc, 0)
        self.assertIn("HELLO_JARVIS_AGENT", out)

if __name__ == "__main__":
    unittest.main()
