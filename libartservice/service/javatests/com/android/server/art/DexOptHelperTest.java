/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.art;

import static com.android.server.art.model.OptimizeResult.DexContainerFileOptimizeResult;
import static com.android.server.art.model.OptimizeResult.PackageOptimizeResult;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.apphibernation.AppHibernationManager;
import android.os.CancellationSignal;
import android.os.PowerManager;

import androidx.test.filters.SmallTest;

import com.android.server.art.model.ArtFlags;
import com.android.server.art.model.OptimizeParams;
import com.android.server.art.model.OptimizeResult;
import com.android.server.pm.PackageManagerLocal;
import com.android.server.pm.pkg.AndroidPackage;
import com.android.server.pm.pkg.AndroidPackageSplit;
import com.android.server.pm.pkg.PackageState;
import com.android.server.pm.pkg.SharedLibrary;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

@SmallTest
@RunWith(MockitoJUnitRunner.StrictStubs.class)
public class DexOptHelperTest {
    private static final String PKG_NAME_FOO = "com.example.foo";
    private static final String PKG_NAME_BAR = "com.example.bar";
    private static final String PKG_NAME_LIB1 = "com.example.lib1";
    private static final String PKG_NAME_LIB2 = "com.example.lib2";
    private static final String PKG_NAME_LIB3 = "com.example.lib3";
    private static final String PKG_NAME_LIB4 = "com.example.lib4";
    private static final String PKG_NAME_LIBBAZ = "com.example.libbaz";

    @Mock private DexOptHelper.Injector mInjector;
    @Mock private PrimaryDexOptimizer mPrimaryDexOptimizer;
    @Mock private SecondaryDexOptimizer mSecondaryDexOptimizer;
    @Mock private AppHibernationManager mAhm;
    @Mock private PowerManager mPowerManager;
    @Mock private PowerManager.WakeLock mWakeLock;
    @Mock private PackageManagerLocal.FilteredSnapshot mSnapshot;
    private PackageState mPkgStateFoo;
    private PackageState mPkgStateBar;
    private PackageState mPkgStateLib1;
    private PackageState mPkgStateLib2;
    private PackageState mPkgStateLib4;
    private PackageState mPkgStateLibbaz;
    private AndroidPackage mPkgFoo;
    private AndroidPackage mPkgBar;
    private AndroidPackage mPkgLib1;
    private AndroidPackage mPkgLib2;
    private AndroidPackage mPkgLib4;
    private AndroidPackage mPkgLibbaz;
    private CancellationSignal mCancellationSignal;
    private ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private List<DexContainerFileOptimizeResult> mPrimaryResults;
    private List<DexContainerFileOptimizeResult> mSecondaryResults;
    private OptimizeParams mParams;
    private List<String> mRequestedPackages;
    private DexOptHelper mDexOptHelper;

    @Before
    public void setUp() throws Exception {
        lenient().when(mInjector.getAppHibernationManager()).thenReturn(mAhm);
        lenient().when(mInjector.getPowerManager()).thenReturn(mPowerManager);

        lenient()
                .when(mPowerManager.newWakeLock(eq(PowerManager.PARTIAL_WAKE_LOCK), any()))
                .thenReturn(mWakeLock);

        lenient().when(mAhm.isHibernatingGlobally(any())).thenReturn(false);
        lenient().when(mAhm.isOatArtifactDeletionEnabled()).thenReturn(true);

        mCancellationSignal = new CancellationSignal();

        preparePackagesAndLibraries();

        mPrimaryResults = createResults("/data/app/foo/base.apk", false /* partialFailure */);
        mSecondaryResults =
                createResults("/data/user_de/0/foo/foo.apk", false /* partialFailure */);

        lenient()
                .when(mInjector.getPrimaryDexOptimizer(any(), any(), any(), any()))
                .thenReturn(mPrimaryDexOptimizer);
        lenient().when(mPrimaryDexOptimizer.dexopt()).thenReturn(mPrimaryResults);

        lenient()
                .when(mInjector.getSecondaryDexOptimizer(any(), any(), any(), any()))
                .thenReturn(mSecondaryDexOptimizer);
        lenient().when(mSecondaryDexOptimizer.dexopt()).thenReturn(mSecondaryResults);

        mParams = new OptimizeParams.Builder("install")
                          .setCompilerFilter("speed-profile")
                          .setFlags(ArtFlags.FLAG_FOR_SECONDARY_DEX
                                          | ArtFlags.FLAG_SHOULD_INCLUDE_DEPENDENCIES,
                                  ArtFlags.FLAG_FOR_SECONDARY_DEX
                                          | ArtFlags.FLAG_SHOULD_INCLUDE_DEPENDENCIES)
                          .build();

        mDexOptHelper = new DexOptHelper(mInjector);
    }

    @Test
    public void testDexopt() throws Exception {
        // Only package libbaz fails.
        var failingPrimaryDexOptimizer = mock(PrimaryDexOptimizer.class);
        List<DexContainerFileOptimizeResult> partialFailureResults =
                createResults("/data/app/foo/base.apk", true /* partialFailure */);
        lenient().when(failingPrimaryDexOptimizer.dexopt()).thenReturn(partialFailureResults);
        when(mInjector.getPrimaryDexOptimizer(same(mPkgStateLibbaz), any(), any(), any()))
                .thenReturn(failingPrimaryDexOptimizer);

        OptimizeResult result = mDexOptHelper.dexopt(
                mSnapshot, mRequestedPackages, mParams, mCancellationSignal, mExecutor);

        assertThat(result.getRequestedCompilerFilter()).isEqualTo("speed-profile");
        assertThat(result.getReason()).isEqualTo("install");
        assertThat(result.getFinalStatus()).isEqualTo(OptimizeResult.OPTIMIZE_FAILED);

        // The requested packages must come first.
        assertThat(result.getPackageOptimizeResults()).hasSize(6);
        checkPackageResult(result, 0 /* index */, PKG_NAME_FOO, OptimizeResult.OPTIMIZE_PERFORMED,
                List.of(mPrimaryResults, mSecondaryResults));
        checkPackageResult(result, 1 /* index */, PKG_NAME_BAR, OptimizeResult.OPTIMIZE_PERFORMED,
                List.of(mPrimaryResults, mSecondaryResults));
        checkPackageResult(result, 2 /* index */, PKG_NAME_LIBBAZ, OptimizeResult.OPTIMIZE_FAILED,
                List.of(partialFailureResults, mSecondaryResults));
        checkPackageResult(result, 3 /* index */, PKG_NAME_LIB1, OptimizeResult.OPTIMIZE_PERFORMED,
                List.of(mPrimaryResults, mSecondaryResults));
        checkPackageResult(result, 4 /* index */, PKG_NAME_LIB2, OptimizeResult.OPTIMIZE_PERFORMED,
                List.of(mPrimaryResults, mSecondaryResults));
        checkPackageResult(result, 5 /* index */, PKG_NAME_LIB4, OptimizeResult.OPTIMIZE_PERFORMED,
                List.of(mPrimaryResults, mSecondaryResults));

        // The order matters. It should acquire the wake lock only once, at the beginning, and
        // release the wake lock at the end. When running in a single thread, it should dexopt
        // primary dex files and the secondary dex files together for each package, and it should
        // dexopt requested packages, in the given order, and then dexopt dependencies.
        InOrder inOrder = inOrder(mInjector, mWakeLock);
        inOrder.verify(mWakeLock).setWorkSource(any());
        inOrder.verify(mWakeLock).acquire(anyLong());
        inOrder.verify(mInjector).getPrimaryDexOptimizer(
                same(mPkgStateFoo), same(mPkgFoo), same(mParams), same(mCancellationSignal));
        inOrder.verify(mInjector).getSecondaryDexOptimizer(
                same(mPkgStateFoo), same(mPkgFoo), same(mParams), same(mCancellationSignal));
        inOrder.verify(mInjector).getPrimaryDexOptimizer(
                same(mPkgStateBar), same(mPkgBar), same(mParams), same(mCancellationSignal));
        inOrder.verify(mInjector).getSecondaryDexOptimizer(
                same(mPkgStateBar), same(mPkgBar), same(mParams), same(mCancellationSignal));
        inOrder.verify(mInjector).getPrimaryDexOptimizer(
                same(mPkgStateLibbaz), same(mPkgLibbaz), same(mParams), same(mCancellationSignal));
        inOrder.verify(mInjector).getSecondaryDexOptimizer(
                same(mPkgStateLibbaz), same(mPkgLibbaz), same(mParams), same(mCancellationSignal));
        inOrder.verify(mInjector).getPrimaryDexOptimizer(
                same(mPkgStateLib1), same(mPkgLib1), same(mParams), same(mCancellationSignal));
        inOrder.verify(mInjector).getSecondaryDexOptimizer(
                same(mPkgStateLib1), same(mPkgLib1), same(mParams), same(mCancellationSignal));
        inOrder.verify(mInjector).getPrimaryDexOptimizer(
                same(mPkgStateLib2), same(mPkgLib2), same(mParams), same(mCancellationSignal));
        inOrder.verify(mInjector).getSecondaryDexOptimizer(
                same(mPkgStateLib2), same(mPkgLib2), same(mParams), same(mCancellationSignal));
        inOrder.verify(mInjector).getPrimaryDexOptimizer(
                same(mPkgStateLib4), same(mPkgLib4), same(mParams), same(mCancellationSignal));
        inOrder.verify(mInjector).getSecondaryDexOptimizer(
                same(mPkgStateLib4), same(mPkgLib4), same(mParams), same(mCancellationSignal));
        inOrder.verify(mWakeLock).release();

        verifyNoMoreDexopt(6 /* expectedPrimaryTimes */, 6 /* expectedSecondaryTimes */);

        verifyNoMoreInteractions(mWakeLock);
    }

    @Test
    public void testDexoptNoDependencies() throws Exception {
        mParams = new OptimizeParams.Builder("install")
                          .setCompilerFilter("speed-profile")
                          .setFlags(ArtFlags.FLAG_FOR_SECONDARY_DEX,
                                  ArtFlags.FLAG_FOR_SECONDARY_DEX
                                          | ArtFlags.FLAG_SHOULD_INCLUDE_DEPENDENCIES)
                          .build();

        OptimizeResult result = mDexOptHelper.dexopt(
                mSnapshot, mRequestedPackages, mParams, mCancellationSignal, mExecutor);

        assertThat(result.getPackageOptimizeResults()).hasSize(3);
        checkPackageResult(result, 0 /* index */, PKG_NAME_FOO, OptimizeResult.OPTIMIZE_PERFORMED,
                List.of(mPrimaryResults, mSecondaryResults));
        checkPackageResult(result, 1 /* index */, PKG_NAME_BAR, OptimizeResult.OPTIMIZE_PERFORMED,
                List.of(mPrimaryResults, mSecondaryResults));
        checkPackageResult(result, 2 /* index */, PKG_NAME_LIBBAZ,
                OptimizeResult.OPTIMIZE_PERFORMED, List.of(mPrimaryResults, mSecondaryResults));

        verifyNoMoreDexopt(3 /* expectedPrimaryTimes */, 3 /* expectedSecondaryTimes */);
    }

    @Test
    public void testDexoptPrimaryOnly() throws Exception {
        mParams = new OptimizeParams.Builder("install")
                          .setCompilerFilter("speed-profile")
                          .setFlags(ArtFlags.FLAG_SHOULD_INCLUDE_DEPENDENCIES,
                                  ArtFlags.FLAG_FOR_SECONDARY_DEX
                                          | ArtFlags.FLAG_SHOULD_INCLUDE_DEPENDENCIES)
                          .build();

        OptimizeResult result = mDexOptHelper.dexopt(
                mSnapshot, mRequestedPackages, mParams, mCancellationSignal, mExecutor);

        assertThat(result.getPackageOptimizeResults()).hasSize(6);
        checkPackageResult(result, 0 /* index */, PKG_NAME_FOO, OptimizeResult.OPTIMIZE_PERFORMED,
                List.of(mPrimaryResults));
        checkPackageResult(result, 1 /* index */, PKG_NAME_BAR, OptimizeResult.OPTIMIZE_PERFORMED,
                List.of(mPrimaryResults));
        checkPackageResult(result, 2 /* index */, PKG_NAME_LIBBAZ,
                OptimizeResult.OPTIMIZE_PERFORMED, List.of(mPrimaryResults));
        checkPackageResult(result, 3 /* index */, PKG_NAME_LIB1, OptimizeResult.OPTIMIZE_PERFORMED,
                List.of(mPrimaryResults));
        checkPackageResult(result, 4 /* index */, PKG_NAME_LIB2, OptimizeResult.OPTIMIZE_PERFORMED,
                List.of(mPrimaryResults));
        checkPackageResult(result, 5 /* index */, PKG_NAME_LIB4, OptimizeResult.OPTIMIZE_PERFORMED,
                List.of(mPrimaryResults));

        verifyNoMoreDexopt(6 /* expectedPrimaryTimes */, 0 /* expectedSecondaryTimes */);
    }

    @Test
    public void testDexoptPrimaryOnlyNoDependencies() throws Exception {
        mParams = new OptimizeParams.Builder("install")
                          .setCompilerFilter("speed-profile")
                          .setFlags(0,
                                  ArtFlags.FLAG_FOR_SECONDARY_DEX
                                          | ArtFlags.FLAG_SHOULD_INCLUDE_DEPENDENCIES)
                          .build();

        OptimizeResult result = mDexOptHelper.dexopt(
                mSnapshot, mRequestedPackages, mParams, mCancellationSignal, mExecutor);

        assertThat(result.getPackageOptimizeResults()).hasSize(3);
        checkPackageResult(result, 0 /* index */, PKG_NAME_FOO, OptimizeResult.OPTIMIZE_PERFORMED,
                List.of(mPrimaryResults));
        checkPackageResult(result, 1 /* index */, PKG_NAME_BAR, OptimizeResult.OPTIMIZE_PERFORMED,
                List.of(mPrimaryResults));
        checkPackageResult(result, 2 /* index */, PKG_NAME_LIBBAZ,
                OptimizeResult.OPTIMIZE_PERFORMED, List.of(mPrimaryResults));

        verifyNoMoreDexopt(3 /* expectedPrimaryTimes */, 0 /* expectedSecondaryTimes */);
    }

    @Test
    public void testDexoptCancelledBetweenDex2oatInvocations() throws Exception {
        when(mPrimaryDexOptimizer.dexopt()).thenAnswer(invocation -> {
            mCancellationSignal.cancel();
            return mPrimaryResults;
        });

        OptimizeResult result = mDexOptHelper.dexopt(
                mSnapshot, mRequestedPackages, mParams, mCancellationSignal, mExecutor);

        assertThat(result.getFinalStatus()).isEqualTo(OptimizeResult.OPTIMIZE_CANCELLED);

        assertThat(result.getPackageOptimizeResults()).hasSize(6);
        checkPackageResult(result, 0 /* index */, PKG_NAME_FOO, OptimizeResult.OPTIMIZE_CANCELLED,
                List.of(mPrimaryResults));
        checkPackageResult(
                result, 1 /* index */, PKG_NAME_BAR, OptimizeResult.OPTIMIZE_CANCELLED, List.of());
        checkPackageResult(result, 2 /* index */, PKG_NAME_LIBBAZ,
                OptimizeResult.OPTIMIZE_CANCELLED, List.of());
        checkPackageResult(
                result, 3 /* index */, PKG_NAME_LIB1, OptimizeResult.OPTIMIZE_CANCELLED, List.of());
        checkPackageResult(
                result, 4 /* index */, PKG_NAME_LIB2, OptimizeResult.OPTIMIZE_CANCELLED, List.of());
        checkPackageResult(
                result, 5 /* index */, PKG_NAME_LIB4, OptimizeResult.OPTIMIZE_CANCELLED, List.of());

        verify(mInjector).getPrimaryDexOptimizer(
                same(mPkgStateFoo), same(mPkgFoo), same(mParams), same(mCancellationSignal));

        verifyNoMoreDexopt(1 /* expectedPrimaryTimes */, 0 /* expectedSecondaryTimes */);
    }

    @Test
    public void testDexoptNoCode() throws Exception {
        when(mPkgFoo.getSplits().get(0).isHasCode()).thenReturn(false);

        mRequestedPackages = List.of(PKG_NAME_FOO);
        OptimizeResult result = mDexOptHelper.dexopt(
                mSnapshot, mRequestedPackages, mParams, mCancellationSignal, mExecutor);

        assertThat(result.getFinalStatus()).isEqualTo(OptimizeResult.OPTIMIZE_SKIPPED);
        assertThat(result.getPackageOptimizeResults()).hasSize(1);
        checkPackageResult(
                result, 0 /* index */, PKG_NAME_FOO, OptimizeResult.OPTIMIZE_SKIPPED, List.of());

        verifyNoDexopt();
    }

    @Test
    public void testDexoptLibraryNoCode() throws Exception {
        when(mPkgLib1.getSplits().get(0).isHasCode()).thenReturn(false);

        mRequestedPackages = List.of(PKG_NAME_FOO);
        OptimizeResult result = mDexOptHelper.dexopt(
                mSnapshot, mRequestedPackages, mParams, mCancellationSignal, mExecutor);

        assertThat(result.getFinalStatus()).isEqualTo(OptimizeResult.OPTIMIZE_PERFORMED);
        assertThat(result.getPackageOptimizeResults()).hasSize(1);
        checkPackageResult(result, 0 /* index */, PKG_NAME_FOO, OptimizeResult.OPTIMIZE_PERFORMED,
                List.of(mPrimaryResults, mSecondaryResults));

        verifyNoMoreDexopt(1 /* expectedPrimaryTimes */, 1 /* expectedSecondaryTimes */);
    }

    @Test
    public void testDexoptIsHibernating() throws Exception {
        lenient().when(mAhm.isHibernatingGlobally(PKG_NAME_FOO)).thenReturn(true);

        mRequestedPackages = List.of(PKG_NAME_FOO);
        OptimizeResult result = mDexOptHelper.dexopt(
                mSnapshot, mRequestedPackages, mParams, mCancellationSignal, mExecutor);

        assertThat(result.getFinalStatus()).isEqualTo(OptimizeResult.OPTIMIZE_SKIPPED);
        checkPackageResult(
                result, 0 /* index */, PKG_NAME_FOO, OptimizeResult.OPTIMIZE_SKIPPED, List.of());

        verifyNoDexopt();
    }

    @Test
    public void testDexoptIsHibernatingButOatArtifactDeletionDisabled() throws Exception {
        lenient().when(mAhm.isHibernatingGlobally(PKG_NAME_FOO)).thenReturn(true);
        lenient().when(mAhm.isOatArtifactDeletionEnabled()).thenReturn(false);

        OptimizeResult result = mDexOptHelper.dexopt(
                mSnapshot, mRequestedPackages, mParams, mCancellationSignal, mExecutor);

        assertThat(result.getPackageOptimizeResults()).hasSize(6);
        checkPackageResult(result, 0 /* index */, PKG_NAME_FOO, OptimizeResult.OPTIMIZE_PERFORMED,
                List.of(mPrimaryResults, mSecondaryResults));
        checkPackageResult(result, 1 /* index */, PKG_NAME_BAR, OptimizeResult.OPTIMIZE_PERFORMED,
                List.of(mPrimaryResults, mSecondaryResults));
        checkPackageResult(result, 2 /* index */, PKG_NAME_LIBBAZ,
                OptimizeResult.OPTIMIZE_PERFORMED, List.of(mPrimaryResults, mSecondaryResults));
        checkPackageResult(result, 3 /* index */, PKG_NAME_LIB1, OptimizeResult.OPTIMIZE_PERFORMED,
                List.of(mPrimaryResults, mSecondaryResults));
        checkPackageResult(result, 4 /* index */, PKG_NAME_LIB2, OptimizeResult.OPTIMIZE_PERFORMED,
                List.of(mPrimaryResults, mSecondaryResults));
        checkPackageResult(result, 5 /* index */, PKG_NAME_LIB4, OptimizeResult.OPTIMIZE_PERFORMED,
                List.of(mPrimaryResults, mSecondaryResults));
    }

    @Test
    public void testDexoptAlwaysReleasesWakeLock() throws Exception {
        when(mPrimaryDexOptimizer.dexopt()).thenThrow(IllegalStateException.class);

        try {
            mDexOptHelper.dexopt(
                    mSnapshot, mRequestedPackages, mParams, mCancellationSignal, mExecutor);
        } catch (Exception ignored) {
        }

        verify(mWakeLock).release();
    }

    @Test(expected = IllegalArgumentException.class)
    public void testDexoptPackageNotFound() throws Exception {
        when(mSnapshot.getPackageState(any())).thenReturn(null);

        mDexOptHelper.dexopt(
                mSnapshot, mRequestedPackages, mParams, mCancellationSignal, mExecutor);

        verifyNoDexopt();
    }

    @Test(expected = IllegalArgumentException.class)
    public void testDexoptNoPackage() throws Exception {
        lenient().when(mPkgStateFoo.getAndroidPackage()).thenReturn(null);

        mDexOptHelper.dexopt(
                mSnapshot, mRequestedPackages, mParams, mCancellationSignal, mExecutor);

        verifyNoDexopt();
    }

    private AndroidPackage createPackage() {
        AndroidPackage pkg = mock(AndroidPackage.class);
        var baseSplit = mock(AndroidPackageSplit.class);
        lenient().when(baseSplit.isHasCode()).thenReturn(true);
        lenient().when(pkg.getSplits()).thenReturn(List.of(baseSplit));
        return pkg;
    }

    private PackageState createPackageState(String packageName, List<SharedLibrary> deps) {
        PackageState pkgState = mock(PackageState.class);
        lenient().when(pkgState.getPackageName()).thenReturn(packageName);
        lenient().when(pkgState.getAppId()).thenReturn(12345);
        lenient().when(pkgState.getUsesLibraries()).thenReturn(deps);
        AndroidPackage pkg = createPackage();
        lenient().when(pkgState.getAndroidPackage()).thenReturn(pkg);
        return pkgState;
    }

    private SharedLibrary createLibrary(
            String libraryName, String packageName, List<SharedLibrary> deps) {
        SharedLibrary library = mock(SharedLibrary.class);
        lenient().when(library.getName()).thenReturn(libraryName);
        lenient().when(library.getPackageName()).thenReturn(packageName);
        lenient().when(library.getDependencies()).thenReturn(deps);
        return library;
    }

    private void preparePackagesAndLibraries() {
        // Dependency graph:
        //                foo                bar
        //                 |                  |
        //            lib1a (lib1)       lib1b (lib1)       lib1c (lib1)
        //               /   \             /   \                  |
        //              /     \           /     \                 |
        //  libbaz (libbaz)    lib2 (lib2)    lib4 (lib4)    lib3 (lib3)
        //
        // "lib1a", "lib1b", and "lib1c" belong to the same package "lib1".

        mRequestedPackages = List.of(PKG_NAME_FOO, PKG_NAME_BAR, PKG_NAME_LIBBAZ);

        SharedLibrary libbaz = createLibrary("libbaz", PKG_NAME_LIBBAZ, List.of());
        SharedLibrary lib4 = createLibrary("lib4", PKG_NAME_LIB4, List.of());
        SharedLibrary lib3 = createLibrary("lib3", PKG_NAME_LIB3, List.of());
        SharedLibrary lib2 = createLibrary("lib2", PKG_NAME_LIB2, List.of());
        SharedLibrary lib1a = createLibrary("lib1a", PKG_NAME_LIB1, List.of(libbaz, lib2));
        SharedLibrary lib1b = createLibrary("lib1b", PKG_NAME_LIB1, List.of(lib2, lib4));
        SharedLibrary lib1c = createLibrary("lib1c", PKG_NAME_LIB1, List.of(lib3));

        mPkgStateFoo = createPackageState(PKG_NAME_FOO, List.of(lib1a));
        mPkgFoo = mPkgStateFoo.getAndroidPackage();
        lenient().when(mSnapshot.getPackageState(PKG_NAME_FOO)).thenReturn(mPkgStateFoo);

        mPkgStateBar = createPackageState(PKG_NAME_BAR, List.of(lib1b));
        mPkgBar = mPkgStateBar.getAndroidPackage();
        lenient().when(mSnapshot.getPackageState(PKG_NAME_BAR)).thenReturn(mPkgStateBar);

        mPkgStateLib1 = createPackageState(PKG_NAME_LIB1, List.of(libbaz, lib2, lib3, lib4));
        mPkgLib1 = mPkgStateLib1.getAndroidPackage();
        lenient().when(mSnapshot.getPackageState(PKG_NAME_LIB1)).thenReturn(mPkgStateLib1);

        mPkgStateLib2 = createPackageState(PKG_NAME_LIB2, List.of());
        mPkgLib2 = mPkgStateLib2.getAndroidPackage();
        lenient().when(mSnapshot.getPackageState(PKG_NAME_LIB2)).thenReturn(mPkgStateLib2);

        // This should not be considered as a transitive dependency of any requested package, even
        // though it is a dependency of package "lib1".
        PackageState pkgStateLib3 = createPackageState(PKG_NAME_LIB3, List.of());
        lenient().when(mSnapshot.getPackageState(PKG_NAME_LIB3)).thenReturn(pkgStateLib3);

        mPkgStateLib4 = createPackageState(PKG_NAME_LIB4, List.of());
        mPkgLib4 = mPkgStateLib4.getAndroidPackage();
        lenient().when(mSnapshot.getPackageState(PKG_NAME_LIB4)).thenReturn(mPkgStateLib4);

        mPkgStateLibbaz = createPackageState(PKG_NAME_LIBBAZ, List.of());
        mPkgLibbaz = mPkgStateLibbaz.getAndroidPackage();
        lenient().when(mSnapshot.getPackageState(PKG_NAME_LIBBAZ)).thenReturn(mPkgStateLibbaz);
    }

    private void verifyNoDexopt() {
        verify(mInjector, never()).getPrimaryDexOptimizer(any(), any(), any(), any());
        verify(mInjector, never()).getSecondaryDexOptimizer(any(), any(), any(), any());
    }

    private void verifyNoMoreDexopt(int expectedPrimaryTimes, int expectedSecondaryTimes) {
        verify(mInjector, times(expectedPrimaryTimes))
                .getPrimaryDexOptimizer(any(), any(), any(), any());
        verify(mInjector, times(expectedSecondaryTimes))
                .getSecondaryDexOptimizer(any(), any(), any(), any());
    }

    private List<DexContainerFileOptimizeResult> createResults(
            String dexPath, boolean partialFailure) {
        return List.of(new DexContainerFileOptimizeResult(dexPath, true /* isPrimaryAbi */,
                               "arm64-v8a", "verify", OptimizeResult.OPTIMIZE_PERFORMED,
                               100 /* dex2oatWallTimeMillis */, 400 /* dex2oatCpuTimeMillis */),
                new DexContainerFileOptimizeResult(dexPath, false /* isPrimaryAbi */, "armeabi-v7a",
                        "verify",
                        partialFailure ? OptimizeResult.OPTIMIZE_FAILED
                                       : OptimizeResult.OPTIMIZE_PERFORMED,
                        100 /* dex2oatWallTimeMillis */, 400 /* dex2oatCpuTimeMillis */));
    }

    private void checkPackageResult(OptimizeResult result, int index, String packageName,
            @OptimizeResult.OptimizeStatus int status,
            List<List<DexContainerFileOptimizeResult>> dexContainerFileOptimizeResults) {
        PackageOptimizeResult packageResult = result.getPackageOptimizeResults().get(index);
        assertThat(packageResult.getPackageName()).isEqualTo(packageName);
        assertThat(packageResult.getStatus()).isEqualTo(status);
        assertThat(packageResult.getDexContainerFileOptimizeResults())
                .containsExactlyElementsIn(dexContainerFileOptimizeResults.stream()
                                                   .flatMap(r -> r.stream())
                                                   .collect(Collectors.toList()));
    }
}
