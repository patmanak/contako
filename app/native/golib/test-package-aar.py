"""Host regressions for the native artifact's ELF publication guard."""
import importlib.util
from pathlib import Path
import struct
import unittest

spec = importlib.util.spec_from_file_location('package_aar', Path(__file__).with_name('package-aar.py'))
packaging = importlib.util.module_from_spec(spec)
spec.loader.exec_module(packaging)


def elf(abi, alignment=16384, relro=None, load_size=0x10000):
    elf_class, machine = {'arm64-v8a': (2, 183), 'armeabi-v7a': (1, 40),
                          'x86': (1, 3), 'x86_64': (2, 62)}[abi]
    header_format = '<HHIQQQIHHHHHH' if elf_class == 2 else '<HHIIIIIHHHHHH'
    segment_format = '<IIQQQQQQ' if elf_class == 2 else '<IIIIIIII'
    header_size = 16 + struct.calcsize(header_format)
    segment_size = struct.calcsize(segment_format)
    segments = [(1, 6, 0, 0, 0x100, load_size, alignment)]
    if relro is not None:
        segments.append((0x6474e552, 4, 0, relro[0], 0, relro[1], 1))
    output = bytearray(b'\x7fELF' + bytes([elf_class, 1, 1]) + bytes(9))
    output += struct.pack(header_format, 3, machine, 1, 0, header_size, 0, 0,
                          header_size, segment_size, len(segments), 0, 0, 0)
    for kind, flags, offset, virtual, file_size, memory_size, align in segments:
        values = (kind, flags, offset, virtual, virtual, file_size, memory_size, align) if elf_class == 2 else (
            kind, offset, virtual, virtual, file_size, memory_size, flags, align)
        output += struct.pack(segment_format, *values)
    output += bytes(max(0, 0x100 - len(output)))
    return bytes(output)


class ElfPageAlignmentTest(unittest.TestCase):
    def test_all_four_architectures_accept_16k_load_segments(self):
        for abi in packaging.ABIS:
            with self.subTest(abi=abi):
                self.assertEqual(16384, packaging.elf_page_layout(elf(abi), abi)['minimum_load_alignment'])

    def test_4k_segments_are_rejected_including_64_bit_abis(self):
        for abi in packaging.ABIS:
            with self.subTest(abi=abi), self.assertRaisesRegex(ValueError, 'LOAD_NOT_16K'):
                packaging.elf_page_layout(elf(abi, alignment=4096), abi)

    def test_larger_power_of_two_alignment_is_allowed(self):
        self.assertEqual(65536, packaging.elf_page_layout(elf('arm64-v8a', alignment=65536),
                                                        'arm64-v8a')['minimum_load_alignment'])

    def test_relro_with_adjacent_writable_data_requires_aligned_end(self):
        with self.assertRaisesRegex(ValueError, 'RELRO_NOT_16K'):
            packaging.elf_page_layout(elf('arm64-v8a', relro=(0x4000, 0x1000)), 'arm64-v8a')

    def test_aligned_relro_end_allows_following_writable_data(self):
        self.assertTrue(packaging.elf_page_layout(elf('x86_64', relro=(0x4000, 0x4000)),
                                                 'x86_64')['relro_16k_compatible'])

    def test_unaligned_relro_end_is_allowed_when_no_writable_data_follows(self):
        self.assertTrue(packaging.elf_page_layout(elf('x86_64', relro=(0x4000, 0x1000), load_size=0x5000),
                                                 'x86_64')['relro_16k_compatible'])

    def test_wrong_machine_cannot_be_relabelled_as_another_abi(self):
        with self.assertRaisesRegex(ValueError, 'ARCHITECTURE_MISMATCH'):
            packaging.elf_page_layout(elf('x86_64'), 'arm64-v8a')

    def test_truncated_headers_are_rejected(self):
        for size in (0, 16, 63, 80):
            with self.subTest(size=size), self.assertRaises(ValueError):
                packaging.elf_page_layout(elf('arm64-v8a')[:size], 'arm64-v8a')

    def test_non_power_of_two_alignment_is_rejected(self):
        with self.assertRaisesRegex(ValueError, 'LOAD_NOT_16K'):
            packaging.elf_page_layout(elf('arm64-v8a', alignment=24576), 'arm64-v8a')

    def test_wrong_elf_byte_order_is_rejected(self):
        data = bytearray(elf('arm64-v8a'))
        data[5] = 2
        with self.assertRaisesRegex(ValueError, 'ARCHITECTURE_MISMATCH'):
            packaging.elf_page_layout(bytes(data), 'arm64-v8a')


if __name__ == '__main__':
    unittest.main()
