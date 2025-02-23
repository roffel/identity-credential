/*
 * Copyright 2019 The Android Open Source Project
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

package com.android.identity;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

@RequiresApi(Build.VERSION_CODES.R)
class HardwareIdentityCredentialStore extends IdentityCredentialStore {

    private static final String TAG = "HardwareIdentityCredentialStore";
    private final Context mContext;

    private android.security.identity.IdentityCredentialStore mStore = null;
    private boolean mIsDirectAccess = false;

    private HardwareIdentityCredentialStore(
            @NonNull android.security.identity.IdentityCredentialStore store,
            @NonNull Context context,
            boolean isDirectAccess) {
        mStore = store;
        mContext = context;
        mIsDirectAccess = isDirectAccess;
    }

    static @Nullable IdentityCredentialStore getInstanceIfSupported(@NonNull Context context) {
        android.security.identity.IdentityCredentialStore store =
                android.security.identity.IdentityCredentialStore.getInstance(context);
        if (store != null) {
            return new HardwareIdentityCredentialStore(store, context, false);
        }
        return null;
    }

    @SuppressWarnings("deprecation")
    public static @NonNull IdentityCredentialStore getInstance(@NonNull Context context) {
        IdentityCredentialStore instance = getInstanceIfSupported(context);
        if (instance != null) {
            return instance;
        }
        throw new RuntimeException("HW-backed IdentityCredential not supported");
    }

    static @Nullable IdentityCredentialStore getDirectAccessInstanceIfSupported(
            @NonNull Context context) {
        android.security.identity.IdentityCredentialStore store =
                android.security.identity.IdentityCredentialStore.getDirectAccessInstance(context);
        if (store != null) {
            return new HardwareIdentityCredentialStore(store, context, true);
        }
        return null;
    }

    @SuppressWarnings("deprecation")
    public static @NonNull IdentityCredentialStore getDirectAccessInstance(
            @NonNull Context context) {
        IdentityCredentialStore instance = getDirectAccessInstanceIfSupported(context);
        if (instance != null) {
            return instance;
        }
        throw new RuntimeException("HW-backed direct-access IdentityCredential not supported");
    }

    public static boolean isDirectAccessSupported(@NonNull Context context) {
        IdentityCredentialStore directAccessStore = getDirectAccessInstanceIfSupported(context);
        return directAccessStore != null;
    }

    @SuppressWarnings("deprecation")
    @Override
    public @NonNull String[] getSupportedDocTypes() {
        Set<String> docTypeSet = getCapabilities().getSupportedDocTypes();
        String[] docTypes = new String[docTypeSet.size()];
        int n = 0;
        for (String docType : docTypeSet) {
            docTypes[n++] = docType;
        }
        return docTypes;
    }

    @Override
    public @NonNull WritableIdentityCredential createCredential(
            @NonNull String credentialName,
            @NonNull String docType) throws AlreadyPersonalizedException,
            DocTypeNotSupportedException {
        try {
            android.security.identity.WritableIdentityCredential writableCredential =
                    mStore.createCredential(credentialName, docType);
            return new HardwareWritableIdentityCredential(writableCredential);
        } catch (android.security.identity.AlreadyPersonalizedException e) {
            throw new AlreadyPersonalizedException(e.getMessage(), e);
        } catch (android.security.identity.DocTypeNotSupportedException e) {
            throw new DocTypeNotSupportedException(e.getMessage(), e);
        }
    }

    @Override
    public @Nullable IdentityCredential getCredentialByName(
            @NonNull String credentialName,
            @Ciphersuite int cipherSuite) throws CipherSuiteNotSupportedException {
        try {
            android.security.identity.IdentityCredential credential =
                    mStore.getCredentialByName(credentialName, cipherSuite);
            if (credential == null) {
                return null;
            }
            return new HardwareIdentityCredential(credential);
        } catch (android.security.identity.CipherSuiteNotSupportedException e) {
            throw new CipherSuiteNotSupportedException(e.getMessage(), e);
        }
    }

    @SuppressWarnings("deprecation")
    @Override
    public @Nullable byte[] deleteCredentialByName(@NonNull String credentialName) {
        return mStore.deleteCredentialByName(credentialName);
    }

    SimpleIdentityCredentialStoreCapabilities mCapabilities = null;

    @Override
    public @NonNull
    IdentityCredentialStoreCapabilities getCapabilities() {
        LinkedHashSet<String> supportedDocTypesSet =
                new LinkedHashSet<>(Arrays.asList(mStore.getSupportedDocTypes()));

        if (mCapabilities == null) {
            PackageManager pm = mContext.getPackageManager();
            String featureName = PackageManager.FEATURE_IDENTITY_CREDENTIAL_HARDWARE;
            if (mIsDirectAccess) {
                featureName = PackageManager.FEATURE_IDENTITY_CREDENTIAL_HARDWARE_DIRECT_ACCESS;
            }

            if (pm.hasSystemFeature(featureName,
                    IdentityCredentialStoreCapabilities.FEATURE_VERSION_202101)) {
                mCapabilities = SimpleIdentityCredentialStoreCapabilities.getFeatureVersion202101(
                        mIsDirectAccess,
                        true,
                        supportedDocTypesSet);
            } else {
                mCapabilities = SimpleIdentityCredentialStoreCapabilities.getFeatureVersion202009(
                        mIsDirectAccess,
                        true,
                        supportedDocTypesSet);
            }
        }
        return mCapabilities;
    }
}
