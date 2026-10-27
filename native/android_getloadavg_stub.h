// Android Bionic libc 不提供 getloadavg(3)——它是 glibc/POSIX 可选扩展。
// 当 cgo 开启（CGO_ENABLED=1，Go 对 android/arm 强制外部链接所必需）时，
// github.com/mackerelio/go-osstat/loadavg/loadavg_unix.go 会引用 C.getloadavg，
// 而 NDK 的 <stdlib.h> 没有该声明，cgo 预处理阶段报
// “could not determine what C.getloadavg refers to” 而编译失败。
//
// 本头文件经 CGO_CFLAGS "-include" 强制注入到 v7a 的 cgo 构建：
//   - static inline：定义在调用处内联展开，不产生外部符号，链接期不会 undefined reference；
//   - 返回 -1：表示读取平均负载失败，go-osstat 会优雅降级，Piik 核心投屏不受影响
//     （loadavg 仅用于系统负载统计，非采集/编码/传输路径）。
//
// arm64-v8a 走 CGO_ENABLED=0，不编译该 cgo 文件，无需本存根。
#ifndef PIIK_ANDROID_GETLOADAVG_STUB_H
#define PIIK_ANDROID_GETLOADAVG_STUB_H
#include <stdlib.h>
static inline int getloadavg(double loadavg[], int nelem) {
    (void)loadavg;
    (void)nelem;
    return -1;
}
#endif
