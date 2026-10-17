# CoaraBrowser — Third-Party Notices

この画面は、CoaraBrowser に含まれる第三者コンポーネントの依存関係、バージョン、ライセンスまたは利用条件を表示します。

CoaraBrowser 本体のソースコードは Apache License, Version 2.0 で提供されています。第三者コンポーネントは各行に記載した個別のライセンス／利用条件に従います。すべての第三者ライブラリが Apache License 2.0 で提供されているわけではありません。

## 直接依存関係

| # | Maven アーティファクト | バージョン | ライセンス／利用条件 | 公式情報 |
|---:|---|---:|---|---|
| 1 | `androidx.core:core` | `1.19.1` | Apache License 2.0 | [AndroidX Core](https://developer.android.com/jetpack/androidx/releases/core) |
| 2 | `androidx.startup:startup-runtime` | `1.2.0` | Apache License 2.0 | [AndroidX Startup](https://developer.android.com/jetpack/androidx/releases/startup) |
| 3 | `androidx.profileinstaller:profileinstaller` | `1.4.1` | Apache License 2.0 | [ProfileInstaller](https://developer.android.com/jetpack/androidx/releases/profileinstaller) |
| 4 | `io.noties.markwon:core` | `4.6.2` | Apache License 2.0 | [Markwon](https://github.com/noties/Markwon) |
| 5 | `com.google.zxing:core` | `3.5.4` | Apache License 2.0 | [ZXing](https://github.com/zxing/zxing) |
| 6 | `androidx.emoji2:emoji2` | `1.7.0` | Apache License 2.0 | [AndroidX Emoji2](https://developer.android.com/jetpack/androidx/releases/emoji2) |
| 7 | `androidx.appcompat:appcompat` | `1.8.0` | Apache License 2.0 | [AndroidX AppCompat](https://developer.android.com/jetpack/androidx/releases/appcompat) |
| 8 | `com.google.android.material:material` | `1.14.0` | Apache License 2.0 | [Material Components for Android](https://github.com/material-components/material-components-android) |
| 9 | `androidx.constraintlayout:constraintlayout` | `2.2.2` | Apache License 2.0 | [ConstraintLayout](https://developer.android.com/jetpack/androidx/releases/constraintlayout) |
| 10 | `androidx.coordinatorlayout:coordinatorlayout` | `1.3.0` | Apache License 2.0 | [CoordinatorLayout](https://developer.android.com/jetpack/androidx/releases/coordinatorlayout) |
| 11 | `androidx.recyclerview:recyclerview` | `1.4.0` | Apache License 2.0 | [RecyclerView](https://developer.android.com/jetpack/androidx/releases/recyclerview) |
| 12 | `androidx.swiperefreshlayout:swiperefreshlayout` | `1.2.0` | Apache License 2.0 | [SwipeRefreshLayout](https://developer.android.com/jetpack/androidx/releases/swiperefreshlayout) |
| 13 | `androidx.cardview:cardview` | `1.0.0` | Apache License 2.0 | [CardView](https://developer.android.com/jetpack/androidx/releases/cardview) |
| 14 | `androidx.webkit:webkit` | `1.17.1` | Apache License 2.0 | [WebKit for Android](https://developer.android.com/jetpack/androidx/releases/webkit) |
| 15 | `com.google.mlkit:translate` | `17.0.3` | Google ML Kit Terms of Service and applicable Google APIs terms; not described here as Apache-licensed | [Translation for Android](https://developers.google.com/ml-kit/language/translation/android), [ML Kit Terms](https://developers.google.com/ml-kit/terms) |
| 16 | `com.google.mlkit:language-id` | `17.0.6` | Google ML Kit Terms of Service and applicable Google APIs terms; not described here as Apache-licensed | [Language identification for Android](https://developers.google.com/ml-kit/language/identification/android), [ML Kit Terms](https://developers.google.com/ml-kit/terms) |
| 17 | `androidx.activity:activity` | `1.13.0` | Apache License 2.0 | [AndroidX Activity](https://developer.android.com/jetpack/androidx/releases/activity) |
| 18 | `androidx.viewpager2:viewpager2` | `1.1.0` | Apache License 2.0 | [ViewPager2](https://developer.android.com/jetpack/androidx/releases/viewpager2) |
| 19 | `androidx.fragment:fragment` | `1.9.1` | Apache License 2.0 | [AndroidX Fragment](https://developer.android.com/jetpack/androidx/releases/fragment) |
| 20 | `com.android.tools:desugar_jdk_libs` | `2.1.5` | Upstream license file identifies GNU GPL version 2 and includes a Classpath Exception; applicable notices and file-specific terms must be read from upstream | [Upstream LICENSE](https://github.com/google/desugar_jdk_libs/blob/master/LICENSE), [Project](https://github.com/google/desugar_jdk_libs) |

The Apache-licensed components listed above are subject to the [Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0). Copyright and attribution notices supplied by each upstream project must be retained as required by its license.

## Google ML Kit

The `translate` and `language-id` artifacts are governed by the Google ML Kit Terms of Service, which incorporate the Google APIs Terms of Service. Review the current terms before distributing or using the application: <https://developers.google.com/ml-kit/terms>.

## Android SDK and trademarks

Android SDK tools and platform components have their own terms and notices; see [Android SDK licensing information](https://developer.android.com/license). Android is a trademark of Google LLC. This statement does not grant any trademark rights.

## Scope and updates

This inventory reflects direct declarations currently present in `app/build.gradle`; it does not assert that every transitive artifact has been individually enumerated. Library versions or build configuration changes should be reflected here and in the in-app copy at `app/src/main/assets/LICENSE.MD`.

Copyright 2026 coara-chocomaru
