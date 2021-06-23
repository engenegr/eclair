/*
 * Copyright 2019 ACINQ SAS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package fr.acinq.eclair.wire.internal.channel.version3

import fr.acinq.bitcoin.Satoshi
import fr.acinq.eclair.FeatureSupport.{Mandatory, Optional}
import fr.acinq.eclair.Features.{ChannelRangeQueries, PaymentSecret, VariableLengthOnion}
import fr.acinq.eclair.channel.RemoteParams
import fr.acinq.eclair.{CltvExpiryDelta, Features, MilliSatoshi, UInt64, randomKey}
import fr.acinq.eclair.wire.internal.channel.ChannelCodecsSpec.normal
import fr.acinq.eclair.wire.internal.channel.version3.ChannelCodecs3.Codecs.{DATA_NORMAL_Codec, remoteParamsCodec}
import org.scalatest.funsuite.AnyFunSuite
import scodec.bits.ByteVector

class ChannelCodecs3Spec extends AnyFunSuite {

  test("basic serialization test (NORMAL)") {
    val data = normal
    val bin = DATA_NORMAL_Codec.encode(data).require
    val check = DATA_NORMAL_Codec.decodeValue(bin).require
    assert(data.commitments.localCommit.spec === check.commitments.localCommit.spec)
    assert(data === check)
  }

  test("encode/decode optional shutdown script") {
    val remoteParams = RemoteParams(
      randomKey().publicKey,
      Satoshi(600),
      UInt64(123456L),
      Satoshi(300),
      MilliSatoshi(1000),
      CltvExpiryDelta(42),
      42,
      randomKey().publicKey,
      randomKey().publicKey,
      randomKey().publicKey,
      randomKey().publicKey,
      randomKey().publicKey,
      Features(ChannelRangeQueries -> Optional, VariableLengthOnion -> Mandatory, PaymentSecret -> Mandatory),
      None)
    assert(remoteParamsCodec.decodeValue(remoteParamsCodec.encode(remoteParams).require).require === remoteParams)
    val remoteParams1 = remoteParams.copy(shutdownScript = Some(ByteVector.fromValidHex("deadbeef")))
    assert(remoteParamsCodec.decodeValue(remoteParamsCodec.encode(remoteParams1).require).require === remoteParams1)
  }
}

