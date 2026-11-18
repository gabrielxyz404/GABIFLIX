// Fotografia do VLC sem risco de fechar o app: o VLCKit LANÇA uma exceção do Objective-C ("No video output") quando não há saída de vídeo
// naquele instante, e o Swift não consegue apanhá-la. Esta função apanha-a e devolve um código (0 = ok, -1 = exceção, -2 = sem o método).
// Chamada pelo Swift com @_silgen_name("fp_try_snapshot"): nenhum cabeçalho de ponte é preciso.
#import <Foundation/Foundation.h>
#import <objc/message.h>

int fp_try_snapshot(id player, NSString *path, NSUInteger w, NSUInteger h) {
    @try {
        SEL sel = NSSelectorFromString(@"saveVideoSnapshotAt:withWidth:andHeight:");
        if (![player respondsToSelector:sel]) return -2;
        ((void (*)(id, SEL, NSString *, NSUInteger, NSUInteger))objc_msgSend)(player, sel, path, w, h);
        return 0;
    } @catch (NSException *e) {
        return -1;
    }
}
