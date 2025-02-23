/*
 * Copyright 2022 The Android Open Source Project
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

import android.util.Pair;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.List;
import java.util.OptionalInt;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.KeyAgreement;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import co.nstant.in.cbor.CborBuilder;
import co.nstant.in.cbor.CborDecoder;
import co.nstant.in.cbor.CborException;
import co.nstant.in.cbor.builder.MapBuilder;
import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.Map;
import co.nstant.in.cbor.model.Number;
import co.nstant.in.cbor.model.UnicodeString;
import co.nstant.in.cbor.model.UnsignedInteger;


/**
 * A helper class for encrypting and decrypting messages exchanged with a remote
 * mDL prover, conforming to ISO 18013-5 9.1.1 Session encryption.
 */
final class SessionEncryptionReader {

    private static final String TAG = "SessionEncryptionReader";

    private boolean mSessionEstablishmentSent;

    private final byte[] mEncodedDeviceEngagement;
    private final DataItem mHandover;

    private final PrivateKey mEReaderKeyPrivate;
    private final PublicKey mEReaderKeyPublic;
    private byte[] mEncodedSessionTranscript;

    private SecretKeySpec mSKDevice;
    private SecretKeySpec mSKReader;
    private int mSKDeviceCounter = 1;
    private int mSKReaderCounter = 1;

    /**
     * Creates a new {@link SessionEncryptionReader} object.
     *
     * <p>The <code>DeviceEngagement</code> and <code>Handover</code> CBOR referenced in the
     * parameters below must conform to the CDDL in ISO 18013-5.
     *
     * @param eReaderKeyPrivate the reader private ephemeral key.
     * @param eReaderKeyPublic the reader public ephemeral key.
     * @param encodedDeviceEngagement the bytes of the <code>DeviceEngagement</code> CBOR.
     * @param encodedHandover the bytes of the <code>Handover</code> CBOR.
     */
    public SessionEncryptionReader(@NonNull PrivateKey eReaderKeyPrivate,
            @NonNull PublicKey eReaderKeyPublic,
            @NonNull byte[] encodedDeviceEngagement,
            @NonNull byte[] encodedHandover) {
        mEReaderKeyPrivate = eReaderKeyPrivate;
        mEReaderKeyPublic = eReaderKeyPublic;
        mEncodedDeviceEngagement = encodedDeviceEngagement;
        mHandover = Util.cborDecode(encodedHandover);
    }

    private PublicKey deviceEngagementExtractEDeviceKey(byte[] encodedDeviceEngagement) {
        ByteArrayInputStream bais = new ByteArrayInputStream(encodedDeviceEngagement);
        List<DataItem> dataItems;
        try {
            dataItems = new CborDecoder(bais).decode();
        } catch (CborException e) {
            throw new IllegalArgumentException("Data is not valid CBOR", e);
        }
        if (dataItems.size() != 1) {
            throw new IllegalArgumentException("Expected 1 item, found " + dataItems.size());
        }
        if (!(dataItems.get(0) instanceof Map)) {
            throw new IllegalArgumentException("Item is not a map");
        }
        Map map = (Map) dataItems.get(0);
        DataItem dataItemSecurity = map.get(new UnsignedInteger(1));
        if (!(dataItemSecurity instanceof Array)) {
            throw new IllegalArgumentException("Key 1 (Security) is not set or not array");
        }
        List<DataItem> securityArrayDataItems = ((Array) dataItemSecurity).getDataItems();
        if (securityArrayDataItems.size() < 2) {
            throw new IllegalArgumentException("Security array is shorter than two elements");
        }

        DataItem cipherSuiteDataItem = securityArrayDataItems.get(0);
        if (!(cipherSuiteDataItem instanceof Number)) {
            throw new IllegalArgumentException("Cipher suite not a Number");
        }
        int cipherSuite = ((Number) cipherSuiteDataItem).getValue().intValue();
        if (cipherSuite != 1) {
            throw new IllegalArgumentException("Expected cipher suite 1, got " + cipherSuite);
        }

        DataItem eDeviceKeyBytesDataItem = securityArrayDataItems.get(1);
        if (!(eDeviceKeyBytesDataItem instanceof ByteString)) {
            throw new IllegalArgumentException("eDeviceKeyBytes not a bstr");
        }
        if (eDeviceKeyBytesDataItem.getTag().getValue() != 24) {
            throw new IllegalArgumentException("eDeviceKeyBytes is not tagged with tag 24");
        }
        byte[] eDeviceKeyBytes = ((ByteString) eDeviceKeyBytesDataItem).getBytes();

        DataItem eDeviceKey = Util.cborDecode(eDeviceKeyBytes);
        return Util.coseKeyDecode(eDeviceKey);
    }

    private void ensureSessionEncryptionKeysAndSessionTranscript() {
        if (mSKReader != null) {
            return;
        }

        PublicKey eDeviceKeyPub = deviceEngagementExtractEDeviceKey(mEncodedDeviceEngagement);

        // TODO: See SessionEncryptionDevice#computeEncryptionKeysAndSessionTranscript()
        //  for similar code. Maybe maybe factor into common utility function.
        //
        byte[] encodedEReaderKeyPub = Util.cborEncode(Util.cborBuildCoseKey(mEReaderKeyPublic));
        mEncodedSessionTranscript = Util.cborEncode(new CborBuilder()
                .addArray()
                .add(Util.cborBuildTaggedByteString(mEncodedDeviceEngagement))
                .add(Util.cborBuildTaggedByteString(encodedEReaderKeyPub))
                .add(mHandover)
                .end()
                .build().get(0));

        try {
            KeyAgreement ka = KeyAgreement.getInstance("ECDH");
            ka.init(mEReaderKeyPrivate);
            ka.doPhase(eDeviceKeyPub, true);
            byte[] sharedSecret = ka.generateSecret();

            byte[] sessionTranscriptBytes = Util.cborEncode(
                    Util.cborBuildTaggedByteString(mEncodedSessionTranscript));
            byte[] salt = MessageDigest.getInstance("SHA-256").digest(sessionTranscriptBytes);

            byte[] info = "SKDevice".getBytes(StandardCharsets.UTF_8);
            byte[] derivedKey = Util.computeHkdf("HmacSha256", sharedSecret, salt, info, 32);

            mSKDevice = new SecretKeySpec(derivedKey, "AES");

            info = "SKReader".getBytes(StandardCharsets.UTF_8);
            derivedKey = Util.computeHkdf("HmacSha256", sharedSecret, salt, info, 32);
            mSKReader = new SecretKeySpec(derivedKey, "AES");
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("Error deriving keys", e);
        }
    }

    /**
     * Encrypts a message to the remote mDL prover.
     *
     * <p>This method returns <code>SessionEstablishment</code> CBOR for the first call and
     * <code>SessionData</code> CBOR for subsequent calls. These CBOR data structures are
     * defined in ISO 18013-5 9.1.1 Session encryption.
     *
     * @param messagePlaintext if not <code>null</code>, the message to encrypt and include
     *                         in <code>SessionData</code>.
     * @param statusCode if set, the status code to include in <code>SessionData</code>.
     * @return the bytes of the <code>SessionEstablishment</code> or <code>SessionData</code>
     *     CBOR as described above.
     */
    public @NonNull byte[] encryptMessageToDevice(@Nullable byte[] messagePlaintext,
            @NonNull OptionalInt statusCode) {
        ensureSessionEncryptionKeysAndSessionTranscript();
        byte[] messageCiphertext = null;
        if (messagePlaintext != null) {
            try {
                // The IV and these constants are specified in ISO/IEC 18013-5:2021 clause 9.1.1.5.
                ByteBuffer iv = ByteBuffer.allocate(12);
                iv.putInt(0, 0x00000000);
                iv.putInt(4, 0x00000000);
                iv.putInt(8, mSKReaderCounter);
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                GCMParameterSpec encryptionParameterSpec = new GCMParameterSpec(128, iv.array());
                cipher.init(Cipher.ENCRYPT_MODE, mSKReader, encryptionParameterSpec);
                messageCiphertext = cipher.doFinal(messagePlaintext); // This includes the auth tag
            } catch (BadPaddingException
                    | IllegalBlockSizeException
                    | NoSuchPaddingException
                    | InvalidKeyException
                    | NoSuchAlgorithmException
                    | InvalidAlgorithmParameterException e) {
                throw new IllegalStateException("Error encrypting message", e);
            }
            mSKReaderCounter += 1;
        }

        CborBuilder builder = new CborBuilder();
        MapBuilder<CborBuilder> mapBuilder = builder.addMap();
        if (!mSessionEstablishmentSent) {
            DataItem eReaderKey = Util.cborBuildCoseKey(mEReaderKeyPublic);
            DataItem eReaderKeyBytes = Util.cborBuildTaggedByteString(
                    Util.cborEncode(eReaderKey));
            mapBuilder.put(new UnicodeString("eReaderKey"), eReaderKeyBytes);
            if (messageCiphertext == null) {
                throw new IllegalStateException("Data cannot be empty in initial message");
            }
        }
        if (messageCiphertext != null) {
            mapBuilder.put("data", messageCiphertext);
        }
        if (statusCode.isPresent()) {
            mapBuilder.put("status", statusCode.getAsInt());
        }
        mapBuilder.end();
        byte[] messageData = Util.cborEncode(builder.build().get(0));

        mSessionEstablishmentSent = true;

        return messageData;
    }

    /**
     * Decrypts a message received from the remote mDL prover.
     *
     * <p>This method expects the passed-in data to conform to the <code>SessionData</code>
     * DDL as defined in ISO 18013-5 9.1.1 Session encryption.
     *
     * <p>The return value is a pair of two values where both values are optional. The
     * first element is the decrypted data and the second element is the status.
     *
     * @param messageData the bytes of the <code>SessionData</code> CBOR as described above.
     * @return A pair with the decrypted data and status, as decribed above.
     * @exception IllegalArgumentException if the passed in data does not conform to the CDDL.
     * @exception IllegalStateException if decryption fails.
     */
    public @NonNull Pair<byte[], OptionalInt> decryptMessageFromDevice(
            @NonNull byte[] messageData) {
        ByteArrayInputStream bais = new ByteArrayInputStream(messageData);
        List<DataItem> dataItems;
        try {
            dataItems = new CborDecoder(bais).decode();
        } catch (CborException e) {
            throw new IllegalArgumentException("Data is not valid CBOR", e);
        }
        if (dataItems.size() != 1) {
            throw new IllegalArgumentException("Expected 1 item, found " + dataItems.size());
        }
        if (!(dataItems.get(0) instanceof Map)) {
            throw new IllegalArgumentException("Item is not a map");
        }
        Map map = (Map) dataItems.get(0);

        DataItem dataItemData = map.get(new UnicodeString("data"));
        byte[] messageCiphertext = null;
        if (dataItemData != null) {
            if (!(dataItemData instanceof ByteString)) {
                throw new IllegalArgumentException("data is not a bstr");
            }
            messageCiphertext = ((ByteString) dataItemData).getBytes();
        }

        OptionalInt status = OptionalInt.empty();
        DataItem dataItemStatus = map.get(new UnicodeString("status"));
        if (dataItemStatus != null) {
            if (!(dataItemStatus instanceof Number)) {
                throw new IllegalArgumentException("status is not a number");
            }
            status = OptionalInt.of(((Number) dataItemStatus).getValue().intValue());
        }

        byte[] plainText = null;
        if (messageCiphertext != null) {
            ensureSessionEncryptionKeysAndSessionTranscript();
            ByteBuffer iv = ByteBuffer.allocate(12);
            iv.putInt(0, 0x00000000);
            iv.putInt(4, 0x00000001);
            iv.putInt(8, mSKDeviceCounter);
            try {
                final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, mSKDevice, new GCMParameterSpec(128, iv.array()));
                plainText = cipher.doFinal(messageCiphertext);
            } catch (BadPaddingException
                    | IllegalBlockSizeException
                    | InvalidAlgorithmParameterException
                    | InvalidKeyException
                    | NoSuchAlgorithmException
                    | NoSuchPaddingException e) {
                throw new IllegalStateException("Error decrypting data", e);
            }
            mSKDeviceCounter += 1;
        }

        return new Pair<>(plainText, status);
    }

    /**
     * Gets the number of messages encrypted with
     * {@link #encryptMessageToDevice(byte[], OptionalInt)}.
     *
     * @return Number of messages encrypted.
     */
    public int getNumMessagesEncrypted() {
        return mSKReaderCounter - 1;
    }

    /**
     * Gets the number of messages decrypted with {@link #decryptMessageFromDevice(byte[])}
     *
     * @return Number of messages decrypted.
     */
    public int getNumMessagesDecrypted() {
        return mSKDeviceCounter - 1;
    }

    /**
     * Gets the <code>SessionTranscript</code> CBOR.
     *
     * <p>This CBOR defined in the ISO 18013-5 9.1.5.1 Session transcript.
     *
     * @return the bytes of <code>SessionTranscript</code> CBOR.
     */
    public @NonNull byte[] getSessionTranscript() {
        ensureSessionEncryptionKeysAndSessionTranscript();
        return mEncodedSessionTranscript;
    }
}
