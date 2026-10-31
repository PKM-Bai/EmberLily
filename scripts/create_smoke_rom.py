"""Build an original GBA diagnostic cartridge. Contains no game assets or Nintendo logo.

Requires LLVM clang, ld.lld and llvm-objcopy (the Android NDK provides all three). Output is
local-only and never substitutes for testing a target Fire Emblem build.

On each rendered frame, EWRAM 0x02000000 receives a 32-bit counter and SRAM
0x0E000000 receives its low byte. A flushed battery file must therefore be
32 KiB with byte zero equal to the counter modulo 256. This exercises an
original program's active save writes, not Fire Emblem's in-game save menus.
"""
import argparse
import pathlib
import subprocess
import tempfile

SOURCE = r'''
.syntax unified
.cpu arm7tdmi
.arm
.section .text,"ax",%progbits
.global _start
_start:
    b reset
    .space 156,0
    .ascii "EMBERTEST   "
    .ascii "ELTY"
    .ascii "00"
    .byte 0x96,0,0
    .space 7,0
    .byte 0,0,0,0
reset:
    ldr r0, =0x04000000
    ldr r1, =0x0403
    strh r1, [r0]
    ldr r0, =0x04000084
    mov r1, #0x80
    strh r1, [r0]
    ldr r0, =0x04000080
    ldr r1, =0x2277
    strh r1, [r0]
    ldr r0, =0x04000068
    ldr r1, =0xF080
    strh r1, [r0]
    ldr r0, =0x0400006C
    ldr r1, =0x86D6
    strh r1, [r0]
    mov r8, #0
loop:
    ldr r4, =0x04000006
wait_visible:
    ldrh r0, [r4]
    cmp r0, #160
    bhs wait_visible
wait_blank:
    ldrh r0, [r4]
    cmp r0, #160
    blo wait_blank
    add r8, r8, #1
    ldr r0, =0x02000000
    str r8, [r0]
    @ mGBA 0.10.5 src/gba/memory.c GBAStore8 detects SRAM on this first write.
    @ No SDK identifier or proprietary save-library code is needed.
    ldr r0, =0x0E000000
    strb r8, [r0]
    ldr r0, =0x04000130
    ldrh r3, [r0]
    ldr r0, =0x02000004
    str r3, [r0]
    ldr r0, =0x06000000
    ldr r2, =38400
    ldr r1, =0x1164
    tst r3, #1
    ldreq r1, =0x03FF
fill:
    strh r1, [r0], #2
    subs r2, r2, #1
    bne fill
    b loop
    .ltorg
'''

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--llvm-bin', type=pathlib.Path, required=True)
    parser.add_argument('--output', type=pathlib.Path, default=pathlib.Path('.local/smoke.gba'))
    args = parser.parse_args()
    suffix = '.exe' if (args.llvm_bin/'clang.exe').exists() else ''
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=args.output.parent) as temp:
        directory = pathlib.Path(temp)
        assembly, obj, elf = directory/'smoke.s', directory/'smoke.o', directory/'smoke.elf'
        assembly.write_text(SOURCE, encoding='ascii')
        # Invoke each tool directly: no host runtime, SDK libraries or implicit
        # clang subprocess path lookup is needed for this assembly-only fixture.
        subprocess.run([str(args.llvm_bin/f'clang{suffix}'), '-cc1as',
                        '-triple', 'armv4t-none-eabi', '-target-cpu', 'arm7tdmi',
                        '-filetype', 'obj', str(assembly), '-o', str(obj)], check=True)
        subprocess.run([str(args.llvm_bin/f'ld.lld{suffix}'), '-Ttext=0x08000000',
                        '--entry=_start', str(obj), '-o', str(elf)], check=True)
        subprocess.run([str(args.llvm_bin/f'llvm-objcopy{suffix}'), '-O', 'binary',
                        '--only-section=.text', str(elf), str(args.output)], check=True)
    data = bytearray(args.output.read_bytes())
    data[0xBD] = (-sum(data[0xA0:0xBD])-0x19) & 255
    data.extend(bytes(max(0, 32768-len(data))))
    args.output.write_bytes(data)
    print(f'Created original diagnostic: {args.output} ({len(data)} bytes)')

if __name__ == '__main__':
    main()
