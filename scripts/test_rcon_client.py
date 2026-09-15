"""Local socket fixtures for command/delimiter ordering, fragmentation and UTF-8 frames."""
import struct
import unittest
from rcon_client import MinecraftRCON


def frame(identity, body):
    payload = struct.pack('<ii', identity, 0) + body.encode('utf-8') + b'\0\0'
    return struct.pack('<i', len(payload)) + payload


class SocketFixture:
    def __init__(self):
        self.outgoing = []
        self.reply = bytearray()
        self.delimiter_sent = False

    def sendall(self, raw):
        size, identity, kind = struct.unpack('<iii', raw[:12])
        assert size == len(raw) - 4
        self.outgoing.append((identity, kind, raw[12:-2].decode('utf-8')))
        if kind == 2:
            self.reply.extend(frame(identity, '界' * 1000) + frame(identity, 'second fragment'))
        else:
            # At least the whole first response must have been read before delimiter.
            assert len(self.reply) <= len(frame(identity - 1, 'second fragment'))
            self.delimiter_sent = True
            self.reply.extend(frame(identity, 'Unknown request 0'))

    def recv(self, count):
        count = min(count, 7, len(self.reply))
        out = bytes(self.reply[:count]); del self.reply[:count]; return out


class RconFramingTest(unittest.TestCase):
    def test_utf8_command_and_fragmented_response_use_ordered_delimiter(self):
        client = MinecraftRCON(); client.sock = SocketFixture()
        self.assertEqual('界' * 1000 + 'second fragment', client.command('/say 材質'))
        self.assertEqual([(1, 2, 'say 材質'), (2, 0, '')], client.sock.outgoing)
        self.assertTrue(client.sock.delimiter_sent)
        self.assertEqual(bytearray(), client.sock.reply)

    def test_disconnect_never_becomes_an_empty_success(self):
        client = MinecraftRCON(); client.sock = SocketFixture()
        with self.assertRaises(ConnectionError): client._read()

    def test_truncated_frame_never_becomes_an_empty_success(self):
        client = MinecraftRCON(); client.sock = SocketFixture()
        client.sock.reply.extend(frame(1, 'reply')[:-3])
        with self.assertRaises(ConnectionError): client._read()


if __name__ == '__main__':
    unittest.main()
