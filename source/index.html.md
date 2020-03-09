---
title: Eclair API

language_tabs: # must be one of https://git.io/vQNgJ
  - shell

toc_footers:
  - <a href='https://github.com/lord/slate'>Documentation Powered by Slate</a>

includes:
  - errors

search: true
---

# Introduction

Welcome to the Eclair API, this website contains documentation and code examples about how to interact with the Eclair lightning node via its API.
Feel free to suggest improvements and fixes to this documentation by submitting a pull request to the [repo](https://github.com/ACINQ/eclair).
The API uses [HTTP form data](https://en.wikipedia.org/wiki/POST_(HTTP)#Use_for_submitting_web_forms) and returns JSON encoded object or simple strings if no object is being returned, all errors are handled with a JSON response more info [here](#errors).
All monetary values are in millisatoshi unless stated otherwise.

# Authentication

Eclair uses HTTP Basic authentication and expects to receive the correct header with every request.
To set an API password use the [configuration](https://github.com/ACINQ/eclair/blob/master/eclair-core/src/main/resources/reference.conf).
The rest of this document will use '21satoshi' as password which encoded as _base64_ results in `OjIxc2F0b3NoaQ==`.

<aside class="notice">
 Please note that eclair only expects a password and an empty user name.
</aside>

`Authorization: Base64Encoded("":<eclair_api_password>)`

# GetInfo

## GetInfo

```shell
curl -u :<eclair_api_password> -X POST "http://localhost:8080/getinfo"

# with eclair-cli
eclair-cli getinfo
```

> The above command returns JSON structured like this:

```json
{
   "nodeId":"03864ef025fde8fb587d989186ce6a4a186895ee44a926bfc370e2c366597a3f8f",
   "alias":"ACINQ",
   "chainHash":"06226e46111a0b59caaf126043eb5bbf28c34f3a5e332a1fc7b2b73cf188910f",
   "blockHeight":123456,
   "publicAddresses":[
      "34.239.230.56:9735",
      "of7husrflx7sforh3fw6yqlpwstee3wg5imvvmkp4bz6rbjxtg5nljad.onion:9735"
   ]
}
```

Returns information about this instance such as **nodeId** and current block height as seen by eclair.

### HTTP Request

`POST http://localhost:8080/getinfo`

# Connect

## Connect via URI

```shell
curl -u :<eclair_api_password> -X POST -F uri=<target_uri>  "http://localhost:8080/connect"

# with eclair-cli
eclair-cli connect --uri=<target_uri>
```

> The above command returns:

```shell
connected
```

Connect to another lightning node, this will perform a connection but no channel will be opened.
Note in the _URI_ the port is optional and if missing the default (9735) will be used.

### HTTP Request

`POST http://localhost:8080/connect`

### Parameters

Parameter | Description                          | Optional | Type  
--------- | ------------------------------------ | -------- | ------
uri       | The URI in format 'nodeId@host:port' | No       | String

## Connect manually

```shell
curl -u :<eclair_api_password> -X POST -F nodeId=<node_id> -F host=<host> "http://localhost:8080/connect"

# with eclair-cli
eclair-cli connect --nodeId=<node_id> --host=<host>
```

> The above command returns:

```shell
connected
```

Connect to another lightning node, this will perform a connection but no channel will be opened.

### HTTP Request

`POST http://localhost:8080/connect`

### Parameters

Parameter | Description                                       | Optional | Type
--------- | ------------------------------------------------- | -------- | ---------------------------
nodeId    | The **nodeId** of the node you want to connect to | No       | 33-bytes-HexString (String)
host      | The IPv4 host address of the node                 | No       | String
port      | The port of the node (default: 9735)              | Yes      | Integer

## Connect via NodeId

```shell
curl -u :<eclair_api_password> -X POST -F nodeId=<nodeId>  "http://localhost:8080/connect"

# with eclair-cli
eclair-cli connect --nodeId=<nodeId>
```

> The above command returns:

```shell
connected
```

Connect to another lightning node, this will perform a connection but no channel will be opened.
This API does not require a target address, instead eclair will use one of the addresses published
by the remote peer in his `node_announcement` messages.

### HTTP Request

`POST http://localhost:8080/connect`

### Parameters

Parameter | Description                                       | Optional | Type
--------- | ------------------------------------------------- | -------- | ---------------------------
nodeId    | The **nodeId** of the node you want to connect to | No       | 33-bytes-HexString (String)

## Disconnect

```shell
curl -u :<eclair_api_password> -X POST -F nodeId=<nodeId>  "http://localhost:8080/disconnect"

# with eclair-cli
eclair-cli disconnect --nodeId=<nodeId>
```

> The above command returns:

```shell
disconnecting
```

Disconnect from a peer.

### HTTP Request

`POST http://localhost:8080/disconnect`

### Parameters

Parameter | Description                                            | Optional | Type
--------- | ------------------------------------------------------ | -------- | ---------------------------
nodeId    | The **nodeId** of the node you want to disconnect from | No       | 33-bytes-HexString (String)

# Open

## Open

```shell
curl -X POST -F nodeId=<node_id> -F fundingSatoshis=<funding_satoshis> "http://localhost:8080/open" -u :<eclair_api_password>

# with eclair-cli
eclair-cli open --nodeId=<node_id> --fundingSatoshis=<funding_satoshis>
```

> The above command returns the channelId of the newly created channel:

```shell
created channel e872f515dc5d8a3d61ccbd2127f33141eaa115807271dcc5c5c727f3eca914d3
```

Open a channel to another lightning node, you must specify the target nodeId and the funding satoshis for the new channel. Optionally
you can send to the remote a _pushMsat_ value and you can specify wether this should be a public or private channel (default is set in the config).

### HTTP Request

`POST http://localhost:8080/open`

### Parameters

Parameter             | Description                                                     | Optional | Type
--------------------- | --------------------------------------------------------------- | -------- | ---------------------------
nodeId                | The nodeId of the node you want to connect to                   | No       | 33-bytes-HexString (String)
fundingSatoshis       | Amount of satoshis to spend in the funding of the channel       | No       | Satoshis (Integer)
pushMsat              | Amount of millisatoshi to unilaterally push to the counterparty | Yes      | Millisatoshis (Integer)
fundingFeerateSatByte | Feerate in sat/byte to apply to the funding transaction         | Yes      | Satoshis (Integer)
channelFlags          | Flags for the new channel: 0 = private, 1 = public              | Yes      | Integer
openTimeoutSeconds    | Timeout for the operation to complete                           | Yes      | Seconds (Integer)

# Close

## Close

```shell
curl -u :<eclair_api_password> -X POST -F channelId=<channel> "http://localhost:8080/close"

# with eclair-cli
eclair-cli close --channelId=<channel>
```

> The above command returns:

```shell
ok
```

Initiates a cooperative close for a give channel that belongs to this eclair node, the API returns once the _funding_signed_ message has been negotiated.
If you specified a scriptPubKey then the closing transaction will spend to that address. Note that you must specify at least a _channelId_ **or** _shortChannelId_.

### HTTP Request

`POST http://localhost:8080/close`

### Parameters

Parameter      | Description                                                         | Optional | Type
-------------- | ------------------------------------------------------------------- | -------- | ---------------------------
channelId      | The channelId of the channel you want to close                      | No       | 32-bytes-HexString (String)
shortChannelId | The shortChannelId of the channel you want to close                 | Yes      | ShortChannelId (String)
scriptPubKey   | A serialized scriptPubKey that you want to use to close the channel | Yes      | HexString (String)

## ForceClose

```shell
curl -u :<eclair_api_password> -X POST -F channelId=<channel> "http://localhost:8080/forceclose"

# with eclair-cli
eclair-cli forceclose --channelId=<channel>
```

> The above command returns:

```shell
ok
```

Initiates an unilateral close for a give channel that belongs to this eclair node, once the commitment has been broadcasted the API returns its
transaction id. Note that you must specify at least a _channelId_ **or** _shortChannelId_.

### HTTP Request

`POST http://localhost:8080/forceclose`

### Parameters

Parameter      | Description                                         | Optional | Type
-------------- | --------------------------------------------------- | -------- | ---------------------------
channelId      | The channelId of the channel you want to close      | No       | 32-bytes-HexString (String)
shortChannelId | The shortChannelId of the channel you want to close | Yes      | ShortChannelId (String)

# UpdateRelayFee

## UpdateRelayFee

```shell
curl -u :<eclair_api_password> -X POST -F channelId=<channel> \
     -F feeBaseMsat=<feebase> -F feeProportionalMillionths=<feeproportional> \
     "http://localhost:8080/updaterelayfee"

#eclair-cli
eclair-cli updaterelayfee \
  --channelId=<channel> \
  --feeBaseMsat=<feebase> \
  --feeProportionalMillionths=<feeproportional>
```

> The above command returns:

```shell
ok
```

Updates the fee policy for the specified _channelId_, a new update for this channel will be broadcasted to the network.

### HTTP Request

`POST http://localhost:8080/updaterelayfee`

### Parameters

Parameter                 | Description                                          | Optional | Type
------------------------- | ---------------------------------------------------- | -------- | ---------------------------
channelId                 | The channelId of the channel you want to update      | No       | 32-bytes-HexString (String)
shortChannelId            | The shortChannelId of the channel you want to update | Yes      | ShortChannelId (String)
feeBaseMsat               | The new base fee to use                              | No       | Millisatoshi (Integer)
feeProportionalMillionths | The new proportional fee to use                      | No       | Integer

# Peers

## Peers

```shell
curl -u :<eclair_api_password> -X POST "http://localhost:8080/peers"

# with eclair-cli
eclair-cli peers
```

> The above command returns:

```json
[
   {
      "nodeId":"03864ef025fde8fb587d989186ce6a4a186895ee44a926bfc370e2c366597a3f8f",
      "state":"CONNECTED",
      "address":"34.239.230.56:9735",
      "channels":1
   },
   {
      "nodeId":"039dc0e0b1d25905e44fdf6f8e89755a5e219685840d0bc1d28d3308f9628a3585",
      "state":"DISCONNECTED",
      "channels":1
   }
]
```

Returns the list of currently known peers, both connected and disconnected.

### HTTP Request

`POST http://localhost:8080/peers`

# Channels

## Channels

```shell
curl -u :<eclair_api_password> -X POST "http://localhost:8080/channels"

# with eclair-cli
eclair-cli channels
```

> The above command returns:

```json
[
  {
    "nodeId": "03864ef025fde8fb587d989186ce6a4a186895ee44a926bfc370e2c366597a3f8f",
    "channelId": "56d7d6eda04d80138270c49709f1eadb5ab4939e5061309ccdacdb98ce637d0e",
    "state": "NORMAL",
    "data": {
      "commitments": {
        "localParams": {
          "nodeId": "036d65409c41ab7380a43448f257809e7496b52bf92057c09c4f300cbd61c50d96",
          "channelKeyPath": {
            "path": [
              698411009,
              4289979314,
              8627192,
              3309856639
            ]
          },
          "dustLimit": 546,
          "maxHtlcValueInFlight": 5000000000,
          "channelReserve": 2300,
          "htlcMinimum": 1,
          "toSelfDelay": 144,
          "maxAcceptedHtlcs": 30,
          "isFunder": true,
          "defaultFinalScriptPubKey": "a9148852d917c2f8cdf3eacea8015c35bfe57e98eede87",
          "globalFeatures": "",
          "localFeatures": "82"
        },
        "remoteParams": {
          "nodeId": "03864ef025fde8fb587d989186ce6a4a186895ee44a926bfc370e2c366597a3f8f",
          "dustLimit": 546,
          "maxHtlcValueInFlight": 5000000000,
          "channelReserve": 2300,
          "htlcMinimum": 1,
          "toSelfDelay": 144,
          "maxAcceptedHtlcs": 30,
          "fundingPubKey": "030110991e6e23961f4c013fa70f76317bc75cf38df33ff7b448b510d1c7b09c94",
          "revocationBasepoint": "02a5a32c4dc63d0bb98a8fac8a57fd9d494b1ac843b87db3863d76b7c4bebd9026",
          "paymentBasepoint": "0304db8d9e0a46788e14f19a0be20e33eb198dc7e652f50de53e835a7d82f9a0f7",
          "delayedPaymentBasepoint": "03a5c1e85d9c21f21e8e0d15a44bc7240b7d3ec4469b042e4724a9a36293792351",
          "htlcBasepoint": "037bb6e277a1ab4d242a6e7969bad81055999946f2e7f87daac90c309680c14104",
          "globalFeatures": "",
          "localFeatures": "8a"
        },
        "channelFlags": 1,
        "localCommit": {
          "index": 181,
          "spec": {
            "htlcs": [],
            "feeratePerKw": 2382,
            "toLocal": 227599428,
            "toRemote": 2400572
          },
          "publishableTxs": {
            "commitTx": "0200000000010156d7d6eda04d80138270c49709f1eadb5ab4939e5061309ccdacdb98ce637d0e0000000000ea5719800260090000000000001600148109e081865b9bc47082ebfc52fe17de92ec4fe25372030000000000220020b3e40418334e76872523d3a5cc98ae9a50399408bfe111890be710652c51937b04004830450221008894cb338bf8c7064d5bb8c08099deae980d204c4c888af2c85d6c35e3db6010022057de8f44ea1fec571461ef6517baa25d649edd583e675b2a9a80478eff635b0e01483045022100eecf61753bffeb1ba617c3085fb34fb15e9130c2b8eaf030c40d8bf9e4e7f36e0220197aebae5873b0a64a03c36d00c5108761d4f0b07f730803b3981978c672ee2d01475221030110991e6e23961f4c013fa70f76317bc75cf38df33ff7b448b510d1a7b09c942103647d13a308e012100c9e4a9512065f2c3048f8f0160c665952a8f4f077798a5d52ae2a261420",
            "htlcTxsAndSigs": []
          }
        },
        "remoteCommit": {
          "index": 181,
          "spec": {
            "htlcs": [],
            "feeratePerKw": 2382,
            "toLocal": 2400572,
            "toRemote": 227599428
          },
          "txid": "b11f1947175ce1fc05a1f60378f5cec6345d9acb04763d526c762a2c18892bf8",
          "remotePerCommitmentPoint": "03128d3be2764bf853e19732135f6f2c8b348b1317051c8ea704b25468bbf373db"
        },
        "localChanges": {
          "proposed": [],
          "signed": [],
          "acked": []
        },
        "remoteChanges": {
          "proposed": [],
          "acked": [],
          "signed": []
        },
        "localNextHtlcId": 82,
        "remoteNextHtlcId": 1,
        "originChannels": {},
        "remoteNextCommitInfo": "03a8ce1a067e9f0467f8d34f7d6ac25f90b31518bd3ce724f0a8448f2b8a745c92",
        "commitInput": {
          "outPoint": "0e7d63ce98dbaccd9c3061509e93b45adbeaf10997c4708213804da0edd6d756:0",
          "amountSatoshis": 230000
        },
        "remotePerCommitmentSecrets": null,
        "channelId": "56d7d6eda04d80138270c49709f1eadb5ab4939e5061309ccdacdb98ce637d0e"
      },
      "shortChannelId": "565779x2711x0",
      "buried": true,
      "channelAnnouncement": {
        "nodeSignature1": "304502210085c10f513c26cfe195ff52bce9e309b6fc3f351194a3de64efc25e299bbca1b802207fcba062c4e14f5b49889a5a220a1a189958a484cf2ee51445fea9359e25e44401",
        "nodeSignature2": "30440220791753edd31a391806fdfa02c45560a54aa548fd1d3d8f3b05ca04420fe19dce0220109be60b94346dfb52d9f52187f890e9f04ef701a232ccdbcd99d4c05dabb90b01",
        "bitcoinSignature1": "3045022100a27439d43ba6907cd135a3349ce48dd864f5189b3d42937911fe282fafd703cb0220614388e3e06a7f228c25452e9425d4357bd0363fde9838bed575711bf5e8f4fe01",
        "bitcoinSignature2": "3045022100c8ec9d01fed71d91dc5161b06b4913b79c628c5d19d0c44cecf463a67d3fc0a5022051bd86094a17de9de0cf925ab62d58f39322d0363cb9be0aa7a28fd33a0854a301",
        "features": "",
        "chainHash": "6fe28c0ab6f1b372c1a6a246ae63f74f931e8365e15a089c68d6190000000000",
        "shortChannelId": "565779x2711x0",
        "nodeId1": "036d65409c41ab7380a43448f257809e7496b52bf92057c09c4f300cbd61c50d96",
        "nodeId2": "03864ef025fde8fb587d989186ce6a4a186895ee44a926bfc370e2c366597a3f8f",
        "bitcoinKey1": "03647d13a308e012100c9e4a9512065f2c3048f8f0160c665952a8f4f077798a5d",
        "bitcoinKey2": "030110991e6e23961f4c013fa70f76317bc75cf38df33ff7b448b510d1c7b09c94"
      },
      "channelUpdate": {
        "signature": "3045022100eef406f8282b1115d4122f0e18c3b280378ef5fe1b827dd50fee627deeed986e0220629839a7425185d053d958037ee3817ba67fe6c74ead3ddc4ddb6fb3b5934f1001",
        "chainHash": "6fe28c0ab6f1b372c1a6a246ae63f74f931e8365e15a089c68d6190000000000",
        "shortChannelId": "565779x2711x0",
        "timestamp": 1553521796,
        "messageFlags": 1,
        "channelFlags": 0,
        "cltvExpiryDelta": 144,
        "htlcMinimumMsat": 1,
        "feeBaseMsat": 1000,
        "feeProportionalMillionths": 100,
        "htlcMaximumMsat": 230000000
      }
    }
  }
]
```

Returns the list of local channels, optionally filtered by remote node.

### HTTP Request

`POST http://localhost:8080/channels`

### Parameters

Parameter | Description                                              | Optional | Type
--------- | -------------------------------------------------------- | -------- | ---------------------------
nodeId    | The remote node id to be used as filter for the channels | Yes      | 33-bytes-HexString (String)

## Channel

```shell
curl -u :<eclair_api_password> -X POST -F channelId=<channel>  "http://localhost:8080/channel"

# with eclair-cli
eclair-cli channel --channelId=<channel>
```

> The above command returns:

```json
  {
    "nodeId": "03864ef025fde8fb587d989186ce6a4a186895ee44a926bfc370e2c366597a3f8f",
    "channelId": "56d7d6eda04d80138270c49709f1eadb5ab4939e5061309ccdacdb98ce637d0e",
    "state": "NORMAL",
    "data": {
      "commitments": {
        "localParams": {
          "nodeId": "036d65409c41ab7380a43448f257809e7496b52bf92057c09c4f300cbd61c50d96",
          "channelKeyPath": {
            "path": [
              698411009,
              4289979314,
              8627192,
              3309856639
            ]
          },
          "dustLimit": 546,
          "maxHtlcValueInFlight": 5000000000,
          "channelReserve": 2300,
          "htlcMinimum": 1,
          "toSelfDelay": 144,
          "maxAcceptedHtlcs": 30,
          "isFunder": true,
          "defaultFinalScriptPubKey": "a9148852d917c2f8cdf3eacea8015c35bfe57e98eede87",
          "globalFeatures": "",
          "localFeatures": "82"
        },
        "remoteParams": {
          "nodeId": "03864ef025fde8fb587d989186ce6a4a186895ee44a926bfc370e2c366597a3f8f",
          "dustLimit": 546,
          "maxHtlcValueInFlight": 5000000000,
          "channelReserve": 2300,
          "htlcMinimum": 1,
          "toSelfDelay": 144,
          "maxAcceptedHtlcs": 30,
          "fundingPubKey": "030110991e6e23961f4c013fa70f76317bc75cf38df33ff7b448b510d1c7b09c94",
          "revocationBasepoint": "02a5a32c4dc63d0bb98a8fac8a57fd9d494b1ac843b87db3863d76b7c4bebd9026",
          "paymentBasepoint": "0304db8d9e0a46788e14f19a0be20e33eb198dc7e652f50de53e835a7d82f9a0f7",
          "delayedPaymentBasepoint": "03a5c1e85d9c21f21e8e0d15a44bc7240b7d3ec4469b042e4724a9a36293792351",
          "htlcBasepoint": "037bb6e277a1ab4d242a6e7969bad81055999946f2e7f87daac90c309680c14104",
          "globalFeatures": "",
          "localFeatures": "8a"
        },
        "channelFlags": 1,
        "localCommit": {
          "index": 181,
          "spec": {
            "htlcs": [],
            "feeratePerKw": 2382,
            "toLocal": 227599428,
            "toRemote": 2400572
          },
          "publishableTxs": {
            "commitTx": "0200000000010156d7d6eda04d80138270c49709f1eadb5ab4939e5061309ccdacdb98ce637d0e0000000000ea5719800260090000000000001600148109e081865b9bc47082ebfc52fe17de92ec4fe25372030000000000220020b3e40418334e76872523d3a5cc98ae9a50399408bfe111890be710652c51937b04004830450221008894cb338bf8c7064d5bb8c08099deae980d204c4c888af2c85d6c35e3db6010022057de8f44ea1fec571461ef6517baa25d649edd583e675b2a9a80478eff635b0e01483045022100eecf61753bffeb1ba617c3085fb34fb15e9130c2b8eaf030c40d8bf9e4e7f36e0220197aebae5873b0a64a03c36d00c5108761d4f0b07f730803b3981978c672ee2d01475221030110991e6e23961f4c013fa70f76317bc75cf38df33ff7b448b510d1a7b09c942103647d13a308e012100c9e4a9512065f2c3048f8f0160c665952a8f4f077798a5d52ae2a261420",
            "htlcTxsAndSigs": []
          }
        },
        "remoteCommit": {
          "index": 181,
          "spec": {
            "htlcs": [],
            "feeratePerKw": 2382,
            "toLocal": 2400572,
            "toRemote": 227599428
          },
          "txid": "b11f1947175ce1fc05a1f60378f5cec6345d9acb04763d526c762a2c18892bf8",
          "remotePerCommitmentPoint": "03128d3be2764bf853e19732135f6f2c8b348b1317051c8ea704b25468bbf373db"
        },
        "localChanges": {
          "proposed": [],
          "signed": [],
          "acked": []
        },
        "remoteChanges": {
          "proposed": [],
          "acked": [],
          "signed": []
        },
        "localNextHtlcId": 82,
        "remoteNextHtlcId": 1,
        "originChannels": {},
        "remoteNextCommitInfo": "03a8ce1a067e9f0467f8d34f7d6ac25f90b31518bd3ce724f0a8448f2b8a745c92",
        "commitInput": {
          "outPoint": "0e7d63ce98dbaccd9c3061509e93b45adbeaf10997c4708213804da0edd6d756:0",
          "amountSatoshis": 230000
        },
        "remotePerCommitmentSecrets": null,
        "channelId": "56d7d6eda04d80138270c49709f1eadb5ab4939e5061309ccdacdb98ce637d0e"
      },
      "shortChannelId": "565779x2711x0",
      "buried": true,
      "channelAnnouncement": {
        "nodeSignature1": "304502210085c10f513c26cfe195ff52bce9e309b6fc3f351194a3de64efc25e299bbca1b802207fcba062c4e14f5b49889a5a220a1a189958a484cf2ee51445fea9359e25e44401",
        "nodeSignature2": "30440220791753edd31a391806fdfa02c45560a54aa548fd1d3d8f3b05ca04420fe19dce0220109be60b94346dfb52d9f52187f890e9f04ef701a232ccdbcd99d4c05dabb90b01",
        "bitcoinSignature1": "3045022100a27439d43ba6907cd135a3349ce48dd864f5189b3d42937911fe282fafd703cb0220614388e3e06a7f228c25452e9425d4357bd0363fde9838bed575711bf5e8f4fe01",
        "bitcoinSignature2": "3045022100c8ec9d01fed71d91dc5161b06b4913b79c628c5d19d0c44cecf463a67d3fc0a5022051bd86094a17de9de0cf925ab62d58f39322d0363cb9be0aa7a28fd33a0854a301",
        "features": "",
        "chainHash": "6fe28c0ab6f1b372c1a6a246ae63f74f931e8365e15a089c68d6190000000000",
        "shortChannelId": "565779x2711x0",
        "nodeId1": "036d65409c41ab7380a43448f257809e7496b52bf92057c09c4f300cbd61c50d96",
        "nodeId2": "03864ef025fde8fb587d989186ce6a4a186895ee44a926bfc370e2c366597a3f8f",
        "bitcoinKey1": "03647d13a308e012100c9e4a9512065f2c3048f8f0160c665952a8f4f077798a5d",
        "bitcoinKey2": "030110991e6e23961f4c013fa70f76317bc75cf38df33ff7b448b510d1c7b09c94"
      },
      "channelUpdate": {
        "signature": "3045022100eef406f8282b1115d4122f0e18c3b280378ef5fe1b827dd50fee627deeed986e0220629839a7425185d053d958037ee3817ba67fe6c74ead3ddc4ddb6fb3b5934f1001",
        "chainHash": "6fe28c0ab6f1b372c1a6a246ae63f74f931e8365e15a089c68d6190000000000",
        "shortChannelId": "565779x2711x0",
        "timestamp": 1553521796,
        "messageFlags": 1,
        "channelFlags": 0,
        "cltvExpiryDelta": 144,
        "htlcMinimumMsat": 1,
        "feeBaseMsat": 1000,
        "feeProportionalMillionths": 100,
        "htlcMaximumMsat": 230000000
      }
    }
  }

```

Returns detailed information about a local channel.

### HTTP Request

`POST http://localhost:8080/channel`

### Parameters

Parameter | Description                             | Optional | Type
--------- | --------------------------------------- | -------- | ---------------------------
channelId | The channel id of the requested channel | No       | 32-bytes-HexString (String)

# Network

A set of API to query the network view of eclair.

## AllNodes

```shell
curl -u :<eclair_api_password> -X POST "http://localhost:8080/allnodes"

# with eclair-cli
eclair-cli allnodes
```

> The above command returns:

```json
[
  {
    "signature": "3044022072537adb1a10dab3a4630b578e678f0b5b7f2916af65b5e2a1f71e751b8dddc802200903b8a33fc154b4542acee481446dd674238256d354249d7d10408c413201f201",
    "features": "",
    "timestamp": 1553000829,
    "nodeId": "03a8334aba5660e241468e2f0deb2526bfd50d0e3fe808d882913e39094dc1a028",
    "rgbColor": "#33cccc",
    "alias": "cosmicApotheosis",
    "addresses": [
      "138.229.205.237:9735"
    ]
  },
  {
    "signature": "304502210080e1836a98f69133873a35bea4b9b9d5f5abdad376d526fb2f6ee46aaa77f62b022026ba53b630d76ae9d6c1beec134244a79669a31eb5e6a7cc2038aaefff84382b01",
    "features": "",
    "timestamp": 1553008703,
    "nodeId": "036a54f02d2186de192e4bcec3f7b47adb43b1fa965793387cd2471990ce1d236b",
    "rgbColor": "#1d236b",
    "alias": "capacity.network",
    "addresses": [
      "95.216.16.21:9735",
      "[2a01:4f9:2a:106a:0:0:0:2]:9736"
    ]
  }
]
```

Returns information about all public nodes on the lightning network, this information is taken from the _node_announcement_ network message.

## AllChannels

```shell
curl -u :<eclair_api_password> -X POST "http://localhost:8080/allchannels"

# with eclair-cli
eclair-cli allchannels
```

> The above command returns:

```json
[
  {
    "shortChannelId": "508856x657x0",
    "a": "0206c7b60457550f512d80ecdd9fb6eb798ce7e91bf6ec08ad9c53d72e94ef620d",
    "b": "02f6725f9c1c40333b67faea92fd211c183050f28df32cac3f9d69685fe9665432"
  },
  {
    "shortChannelId": "512733x303x0",
    "a": "024bd94f0425590434538fd21d4e58982f7e9cfd8f339205a73deb9c0e0341f5bd",
    "b": "02eae56f155bae8a8eaab82ddc6fef04d5a79a6b0b0d7bcdd0b60d52f3015af031"
  }
]
```

Returns non detailed information about all public channels in the network.

## AllUpdates

```shell
curl -u :<eclair_api_password> -X POST "http://localhost:8080/allupdates"

# with eclair-cli
eclair-cli allupdates
```

> The above command returns:

```json
[
  {
    "signature": "3045022100d24aeacc7214b78ad7ac2287c53f505ae5a83b149baac914f881ce8be2c2b28f0220132ed855fdd831e0e973f38d28624b9f7dee25e6181755e1572c8a3145dd765f01",
    "chainHash": "6fe28c0ab6f1b372c1a6a246ae63f74f931e8365e15a089c68d6190000000000",
    "shortChannelId": "566780x1734x0",
    "timestamp": 1552908891,
    "messageFlags": 1,
    "channelFlags": 1,
    "cltvExpiryDelta": 144,
    "htlcMinimumMsat": 1000,
    "feeBaseMsat": 1000,
    "feeProportionalMillionths": 1,
    "htlcMaximumMsat": 2970000000
  },
  {
    "signature": "304402201848be0aff000ec279e2d043d1bde8b2c76a9277dab72b9d1523468961c5d78e0220541e233977f2288684dab6ec168e43dc3459d093e901dd6f2b5238c2b888845a01",
    "chainHash": "6fe28c0ab6f1b372c1a6a246ae63f74f931e8365e15a089c68d6190000000000",
    "shortChannelId": "562890x809x0",
    "timestamp": 1552993875,
    "messageFlags": 1,
    "channelFlags": 1,
    "cltvExpiryDelta": 144,
    "htlcMinimumMsat": 1000,
    "feeBaseMsat": 1000,
    "feeProportionalMillionths": 2500,
    "htlcMaximumMsat": 3960000000
  }
]
```

Returns detailed information about all public channels in the network, the information is mostly taken from the _channel_update_ network messages.

<aside class="warning">
The allupdates API is CPU intensive for eclair and might slow down the application.
</aside>

### HTTP Request

`POST http://localhost:8080/allupdates`

### Parameters

Parameter | Description                                                  | Optional | Type
--------- | ------------------------------------------------------------ | -------- | ---------------------------
nodeId    | The node id of the node to be used as filter for the updates | Yes      | 33-bytes-HexString (String)

## NetworkStats

```shell
curl -u :<eclair_api_password> -X POST "http://localhost:8080/networkstats"

# with eclair-cli
eclair-cli networkstats
```

> The above command returns:

```json
{
   "channels":1,
   "nodes":2,
   "capacity":{
      "median":30,
      "percentile5":12,
      "percentile10":14,
      "percentile25":20,
      "percentile75":40,
      "percentile90":46,
      "percentile95":48
   },
   "cltvExpiryDelta":{
      "median":32,
      "percentile5":11,
      "percentile10":13,
      "percentile25":22,
      "percentile75":42,
      "percentile90":51,
      "percentile95":53
   },
   "feeBase":{
      "median":32,
      "percentile5":11,
      "percentile10":13,
      "percentile25":22,
      "percentile75":42,
      "percentile90":51,
      "percentile95":53
   },
   "feeProportional":{
      "median":32,
      "percentile5":11,
      "percentile10":13,
      "percentile25":22,
      "percentile75":42,
      "percentile90":51,
      "percentile95":53
   }
}
```

Returns the median and percentiles statistics about the network graph such as fees, cltvExpiry and capacity.

### HTTP Request

`POST http://localhost:8080/networkstats`

# Payments

Interfaces for sending and receiving payments through eclair.

## CreateInvoice

```shell
curl -u :<eclair_api_password> -X POST -F description=<some_description> \
     -F amountMsat=<some_amount> "http://localhost:8080/createinvoice"

# with eclair-cli
eclair-cli createinvoice --description=<some_description> --amountMsat=<some_amount>
```

> The above command returns:

```json
{
  "prefix": "lnbc",
  "timestamp": 1555416528,
  "nodeId": "036ded9bb8175d0c9fd3fad145965cf5005ec599570f35c682e710dc6001ff605e",
  "serialized": "lnbc1pwtt3wspp5elwc50nuxpzlc87fag53mqm25cv96ek2l26xl4w9eca47gw9504sdq2wpskwctddyxqr4rqrzjqwryaup9lh50kkranzgcdnn2fgvx390wgj5jd07rwr3vxeje0glc7z9rtvqqwngqqqqqqqlgqqqqqeqqjqw5axdq7sfenm4zwplmxneu5q2fggj8yvltrt6ckggpll8qxqdaz5duetw998vy0t3f4guyms439p3e3jhaq3khl7vfzwjwghe5hqtmgpqeme4a",
  "description": "A payment description",
  "paymentHash": "cfdd8a3e7c3045fc1fc9ea291d836aa6185d66cafab46fd5c5ce3b5f21c5a3eb",
  "expiry": 21600,
  "amount": 42000
}
```

Create a **BOLT11** payment invoice.

### HTTP Request

`POST http://localhost:8080/createinvoice`

### Parameters

Parameter       | Description                                                | Optional | Type
--------------- | ---------------------------------------------------------- | -------- | ---------------------------
description     | A description for the invoice                              | No       | String
amountMsat      | Amount in millisatoshi for this invoice                    | Yes      | Millisatoshi (Integer)
expireIn        | Number of seconds that the invoice will be valid           | Yes      | Seconds (Integer)
fallbackAddress | An on-chain fallback address to receive the payment        | Yes      | Bitcoin address (String)
paymentPreimage | A user defined input for the generation of the paymentHash | Yes      | 32-bytes-HexString (String)

## ParseInvoice

```shell
curl -u :<eclair_api_password> -X POST -F invoice=<some_bolt11invoice> "http://localhost:8080/parseinvoice"

# with eclair-cli
eclair-cli parseinvoice --invoice=<some_bolt11invoice>
```

> The above command returns:

```json
{
  "prefix": "lnbc",
  "timestamp": 1555416528,
  "nodeId": "036ded9bb8175d0c9fd3fad145965cf5005ec599570f35c682e710dc6001ff605e",
  "serialized": "lnbc1pwtt3wspp5elwc50nuxpzlc87fag53mqm25cv96ek2l26xl4w9eca47gw9504sdq2wpskwctddyxqr4rqrzjqwryaup9lh50kkranzgcdnn2fgvx390wgj5jd07rwr3vxeje0glc7z9rtvqqwngqqqqqqqlgqqqqqeqqjqw5axdq7sfenm4zwplmxneu5q2fggj8yvltrt6ckggpll8qxqdaz5duetw998vy0t3f4guyms439p3e3jhaq3khl7vfzwjwghe5hqtmgpqeme4a",
  "description": "wassa wassa",
  "paymentHash": "cfdd8a3e7c3045fc1fc9ea291d836aa6185d66cafab46fd5c5ce3b5f21c5a3eb",
  "expiry": 21600
}
```

Returns detailed information about the given invoice.

### HTTP Request

`POST http://localhost:8080/parseinvoice`

### Parameters

Parameter | Description                    | Optional | Type
--------- | ------------------------------ | -------- | ------
invoice   | The invoice you want to decode | No       | String

## PayInvoice

```shell
curl -u :<eclair_api_password> -X POST -F invoice=<some_invoice> "http://localhost:8080/payinvoice"

# with eclair-cli
eclair-cli payinvoice --invoice=<some_invoice>
```

> The above command returns:

```json
"e4227601-38b3-404e-9aa0-75a829e9bec0"
```

Pays a **BOLT11** invoice, in case of failure the payment will be retried up to `maxAttempts` times,
default number of attempts is read from the configuration. The API works in a fire-and-forget fashion where
the unique identifier for this payment attempt is immediately returned to the caller. It's possible to add an
extra `externalId` and this will be returned as part of the [payment data](#getsentinfo).

### HTTP Request

`POST http://localhost:8080/payinvoice`

### Parameters

Parameter       | Description                                                                                    | Optional | Type
--------------- | ---------------------------------------------------------------------------------------------- | -------- | ----------------------
invoice         | The invoice you want to pay                                                                    | No       | String
amountMsat      | Amount to pay if the invoice does not have one                                                 | Yes      | Millisatoshi (Integer)
maxAttempts     | Max number of retries                                                                          | Yes      | Integer
feeThresholdSat | Fee threshold to be paid along the payment route                                               | Yes      | Satoshi (Integer)
maxFeePct       | Max percentage to be paid in fees along the payment route (ignored if below `feeThresholdSat`) | Yes      | Double
externalId      | Extra payment identifier specified by the caller                                               | Yes      | String

## SendToNode

```shell
curl -u :<eclair_api_password> -X POST -F nodeId=<some_node> \
  -F amountMsat=<amount> -F paymentHash=<some_hash> "http://localhost:8080/sendtonode"

# with eclair-cli
eclair-cli sendtonode --nodeId=<some_node> --amountMsat=<amount> --paymentHash=<some_hash>
```

> The above command returns:

```json
"e4227601-38b3-404e-9aa0-75a829e9bec0"
```

Sends money to a node, in case of failure the payment will be retried up to `maxAttempts` times,
default number of attempts is read from the configuration.The API works in a fire-and-forget fashion where
the unique identifier for this payment attempt is immediately returned to the caller.It's possible to add an
extra `externalId` and this will be returned as part of the [payment data](#getsentinfo).

### HTTP Request

`POST http://localhost:8080/sendtonode`

### Parameters

Parameter       | Description                                                                                    | Optional | Type
--------------- | ---------------------------------------------------------------------------------------------- | -------- | ---------------------------
nodeId          | The recipient of this payment                                                                  | No       | 33-bytes-HexString (String)
amountMsat      | Amount to pay                                                                                  | No       | Millisatoshi (Integer)
paymentHash     | The payment hash for this payment                                                              | No       | 32-bytes-HexString (String)
maxAttempts     | Max number of retries                                                                          | Yes      | Integer
feeThresholdSat | Fee threshold to be paid along the payment route                                               | Yes      | Satoshi (Integer)
maxFeePct       | Max percentage to be paid in fees along the payment route (ignored if below `feeThresholdSat`) | Yes      | Double
externalId      | Extra payment identifier specified by the caller                                               | Yes      | String

## SendToRoute

```shell
curl -u :<eclair_api_password> -X POST -F route=node1,node2 \
  -F amountMsat=<amount> \
  -F paymentHash=<some_hash> \
  -F finalCltvExpiry=<some_value> \
  -F invoice=<some_invoice> \
  "http://localhost:8080/sendtoroute"

# with eclair-cli
eclair-cli sendtoroute --route=node1,node2 --amountMsat=<amount> --paymentHash=<some_hash> --finalCltvExpiry=<some_value> --invoice=<some_invoice>
```

> The above command returns:

```json
{
  "paymentId": "15798966-5e95-4dce-84a0-825bd2f2a8d1",
  "parentId": "20b2a854-261a-4e9f-a4ca-59b381aee4bc"
}
```

Sends money to a node forcing the payment to go through the given route, the API works in a fire-and-forget fashion where
the unique identifier for this payment attempt is immediately returned to the caller. The route parameter is a simple list of
nodeIds that the payment will traverse, it can be a json-encoded array (same as [findroute](#findroute) output) or a comma
separated list of nodeIds. Note that the channels between the nodes in the route must be public. It's possible to add an
extra `externalId` and this will be returned as part of the [payment data](#getsentinfo).

This command may also be used to send multipart payments with your own splitting algorithm.
Go to the [wiki](https://github.com/ACINQ/eclair/wiki) for details on how to do that.

### HTTP Request

`POST http://localhost:8080/sendtoroute`

### Parameters

Parameter           | Description                                                   | Optional | Type
------------------- | ------------------------------------------------------------- | -------- | ---------------------------
invoice             | The invoice you want to pay                                   | No       | String
route               | A list of nodeIds from source to destination of the payment   | No       | List of nodeIds
amountMsat          | Amount to pay                                                 | No       | Millisatoshi (Integer)
paymentHash         | The payment hash for this payment                             | No       | 32-bytes-HexString (String)
finalCltvExpiry     | The total CLTV expiry value for this payment                  | No       | Integer
recipientAmountMsat | Total amount that the recipient should receive (if using MPP) | Yes      | Millisatoshi (Integer)
parentId            | Id of the whole payment (if using MPP)                        | Yes      | Java's UUID (String)
externalId          | Extra payment identifier specified by the caller              | Yes      | String

## GetSentInfo

```shell
curl -u :<eclair_api_password> -X POST -F paymentHash=<some_hash> "http://localhost:8080/getsentinfo"

# with eclair-cli
eclair-cli getsentinfo --paymentHash=<some_hash>
```

> The above command returns:

```json
[
  {
    "id": "10886b3c-1803-415e-b97d-84d1003cda31",
    "parentId": "10886b3c-1803-415e-b97d-84d1003cda31",
    "externalId": "cool-id",
    "paymentHash": "836c8795e811fe0306fb4fee3ee3d0c5a2529dd4ac90d6aca4bd5faf26790a93",
    "paymentType": "Standard",
    "amount": 10000,
    "recipientAmount": 10000,
    "recipientNodeId": "037820f011811bcb647a000120e85685dc9706f0afd5614d72086d29704400f63f",
    "createdAt": 1569337970908,
    "paymentRequest": {
      "prefix": "lnbcrt",
      "timestamp": 1569337848,
      "nodeId": "037820f011811bcb647a000120e85685dc9706f0afd5614d72086d29704400f63f",
      "serialized": "lnbcrt100n1pwc5v0cpp5sdkg090gz8lqxphmflhrac7sck3998w54jgddt9yh4067fnep2fsdqqxqrrssk43qe645fs02f84dem2hu7as56c23q928qreslvq65qlpa540gpx9qpgtf8fk9pqqgu7q9g7qgrs8q8g9r7jq0de62nx9dlpk8dh2msqpr894s",
      "description": "",
      "paymentHash": "836c8795e811fe0306fb4fee3ee3d0c5a2529dd4ac90d6aca4bd5faf26790a93",
      "expiry": 3600,
      "amount": 10000
    },
    "status": {
      "type": "failed",
      "failures": [
        {
          "failureType": {
            "name": "Remote"
          },
          "failureMessage": "incorrect payment details or unknown payment hash",
          "failedRoute": [
            {
              "nodeId": "02a9d7232139779a93c72eda7d9519957f7bc6c802d8618ca5a75c0b87ebfbc76f",
              "nextNodeId": "037820f011811bcb647a000120e85685dc9706f0afd5614d72086d29704400f63f",
              "shortChannelId": "1702x2x1"
            }
          ]
        }
      ],
      "completedAt": 1569337971168
    }
  },
  {
    "id": "c85d70da-d159-4a36-938a-c3d2f967f730",
    "parentId": "c85d70da-d159-4a36-938a-c3d2f967f730",
    "paymentHash": "836c8795e811fe0306fb4fee3ee3d0c5a2529dd4ac90d6aca4bd5faf26790a93",
    "paymentType": "Standard",
    "amount": 10000,
    "recipientAmount": 10000,
    "recipientNodeId": "037820f011811bcb647a000120e85685dc9706f0afd5614d72086d29704400f63f",
    "createdAt": 1569337870892,
    "paymentRequest": {
      "prefix": "lnbcrt",
      "timestamp": 1569337848,
      "nodeId": "037820f011811bcb647a000120e85685dc9706f0afd5614d72086d29704400f63f",
      "serialized": "lnbcrt100n1pwc5v0cpp5sdkg090gz8lqxphmflhrac7sck3998w54jgddt9yh4067fnep2fsdqqxqrrssk43qe645fs02f84dem2hu7as56c23q928qreslvq65qlpa540gpx9qpgtf8fk9pqqgu7q9g7qgrs8q8g9r7jq0de62nx9dlpk8dh2msqpr894s",
      "description": "",
      "paymentHash": "836c8795e811fe0306fb4fee3ee3d0c5a2529dd4ac90d6aca4bd5faf26790a93",
      "expiry": 3600,
      "amount": 10000
    },
    "status": {
      "type": "sent",
      "paymentPreimage": "62d6f7517b5066c348cddc0a31ee1ed5e8929de2dbe1c7a24bcb5c7cd0a047e4",
      "feesPaid": 0,
      "route": [
        {
          "nodeId": "02a9d7232139779a93c72eda7d9519957f7bc6c802d8618ca5a75c0b87ebfbc76f",
          "nextNodeId": "037820f011811bcb647a000120e85685dc9706f0afd5614d72086d29704400f63f",
          "shortChannelId": "1702x2x1"
        }
      ],
      "completedAt": 1569337871061
    }
  }
]
```

Returns a list of attempts to send an outgoing payment, the status field contains detailed information about the payment
attempt. If the attempt was unsuccessful the `status` field contains a non empty array of detailed failures descriptions.
The API can be queried by `paymentHash` OR by `uuid`.

### HTTP Request

`POST http://localhost:8080/getsentinfo`

### Parameters

Parameter   | Description                                                    | Optional | Type
----------- | -------------------------------------------------------------- | -------- | ---------------------------
paymentHash | The payment hash common to all payment attepts to be retrieved | No       | 32-bytes-HexString (String)
id          | The unique id of the payment attempt                           | Yes      | Java's UUID (String)

## GetReceivedInfo

```shell
curl -u :<eclair_api_password> -X POST -F paymentHash=<some_hash> "http://localhost:8080/getreceivedinfo"

# with eclair-cli
eclair-cli getreceivedinfo --paymentHash=<some_hash>
```

> The above command returns:

```json
{
  "paymentRequest": {
    "prefix": "lnbcrt",
    "timestamp": 1569338266,
    "nodeId": "02a9d7232139779a93c72eda7d9519957f7bc6c802d8618ca5a75c0b87ebfbc76f",
    "serialized": "lnbcrt10n1pwc5vu6pp52fc79qjkhuz3rys6z87ptxztycyn0a9lgt59htp3h3x602yud0psdq9v9ekgxqrafsdcup549hwl3xkpz4fw6zcsfkng85sswhavn06xlg7jg5tudhj96y9t5ujeudsw2tgchej52ke0u8a8eah0gnkgjtuv4al477gjnuthsplgn8kw",
    "description": "asd",
    "paymentHash": "5271e28256bf0511921a11fc15984b260937f4bf42e85bac31bc4da7a89c6bc3",
    "expiry": 30000,
    "amount": 1000
  },
  "paymentPreimage": "69d56a778eca1619d19d4efc4dbe014ef64005338cb9f6cb88d47e4bac7abc30",
  "paymentType": "Standard",
  "createdAt": 1569338266000,
  "status": {
    "type": "received",
    "amount": 1000,
    "receivedAt": 1569338275797
  }
}
```

Check whether a payment corresponding to the given `paymentHash` has been received, it is possible to use a BOLT11 invoice
as parameter instead of the `paymentHash` but at least one of the two must be specified.

### HTTP Request

`POST http://localhost:8080/getreceivedinfo`

### Parameters

Parameter   | Description                             | Optional | Type
----------- | --------------------------------------- | -------- | ---------------------------
paymentHash | The payment hash you want to check      | No       | 32-bytes-HexString (String)
invoice     | The invoice containing the payment hash | Yes      | String

## GetInvoice

```shell
curl -u :<eclair_api_password> -X POST -F paymentHash=<some_hash> "http://localhost:8080/getinvoice"

# with eclair-cli
eclair-cli getinvoice --description=<some_description> --paymentHash=<some_hash>
```

> The above command returns:

```json
{
  "prefix": "lnbc",
  "timestamp": 1555416528,
  "nodeId": "036ded9bb8175d0c9fd3fad145965cf5005ec599570f35c682e710dc6001ff605e",
  "serialized": "lnbc1pwtt3wspp5elwc50nuxpzlc87fag53mqm25cv96ek2l26xl4w9eca47gw9504sdq2wpskwctddyxqr4rqrzjqwryaup9lh50kkranzgcdnn2fgvx390wgj5jd07rwr3vxeje0glc7z9rtvqqwngqqqqqqqlgqqqqqeqqjqw5axdq7sfenm4zwplmxneu5q2fggj8yvltrt6ckggpll8qxqdaz5duetw998vy0t3f4guyms439p3e3jhaq3khl7vfzwjwghe5hqtmgpqeme4a",
  "description": "A payment description",
  "paymentHash": "cfdd8a3e7c3045fc1fc9ea291d836aa6185d66cafab46fd5c5ce3b5f21c5a3eb",
  "expiry": 21600
}
```

Queries the payment DB for a stored invoice with the given `paymentHash`, if none is found it responds HTTP 404.

### HTTP Request

`POST http://localhost:8080/getinvoice`

### Parameters

Parameter   | Description                                          | Optional | Type
----------- | ---------------------------------------------------- | -------- | ---------------------------
paymentHash | The payment hash of the invoice you want to retrieve | No       | 32-bytes-HexString (String)

## ListInvoices

```shell
curl -u :<eclair_api_password> -X POST "http://localhost:8080/listinvoices"

# with eclair-cli
eclair-cli listinvoices
```

> The above command returns:

```json
[
  {
    "prefix": "lnbc",
    "timestamp": 1555416528,
    "nodeId": "036ded9bb8175d0c9fd3fad145965cf5005ec599570f35c682e710dc6001ff605e",
    "serialized": "lnbc1pwtt3wspp5elwc50nuxpzlc87fag53mqm25cv96ek2l26xl4w9eca47gw9504sdq2wpskwctddyxqr4rqrzjqwryaup9lh50kkranzgcdnn2fgvx390wgj5jd07rwr3vxeje0glc7z9rtvqqwngqqqqqqqlgqqqqqeqqjqw5axdq7sfenm4zwplmxneu5q2fggj8yvltrt6ckggpll8qxqdaz5duetw998vy0t3f4guyms439p3e3jhaq3khl7vfzwjwghe5hqtmgpqeme4a",
    "description": "A payment description",
    "paymentHash": "cfdd8a3e7c3045fc1fc9ea291d836aa6185d66cafab46fd5c5ce3b5f21c5a3eb",
    "expiry": 21600
  },
  {
    "prefix": "lnbc",
    "timestamp": 1555416528,
    "nodeId": "036ded9bb8175d0c9fd3fad145965cf5005ec599570f35c682e710dc6001ff605e",
    "serialized": "lnbc1pwtt3wspp5elwc50nuxpzlc87fag53mqm25cv96ek2l26xl4w9eca47gw9504sdq2wpskwctddyxqr4rqrzjqwryaup9lh50kkranzgcdnn2fgvx390wgj5jd07rwr3vxeje0glc7z9rtvqqwngqqqqqqqlgqqqqqeqqjqw5axdq7sfenm4zwplmxneu5q2fggj8yvltrt6ckggpll8qxqdaz5duetw998vy0t3f4guyms439p3e3jhaq3khl7vfzwjwghe5hqtmgpqeme4a",
    "description": "wassa wassa",
    "paymentHash": "cfdd8a3e7c3045fc1fc9ea291d836aa6185d66cafab46fd5c5ce3b5f21c5a3eb"
  }
]
```

Returns all the **BOLT11** invoices stored.

### HTTP Request

`POST http://localhost:8080/listinvoices`

### Parameters

Parameter | Description                                           | Optional | Type
--------- | ----------------------------------------------------- | -------- | -----------------------------------
from      | Filters elements no older than this unix-timestamp    | Yes      | Unix timestamp in seconds (Integer)
to        | Filters elements no younger than this unix-timestamp  | Yes      | Unix timestamp in seconds (Integer)

## ListPendingInvoices

```shell
curl -u :<eclair_api_password> -X POST "http://localhost:8080/listpendinginvoices"

# with eclair-cli
eclair-cli listpendinginvoices
```

> The above command returns:

```json
[
  {
    "prefix": "lnbc",
    "timestamp": 1555416528,
    "nodeId": "036ded9bb8175d0c9fd3fad145965cf5005ec599570f35c682e710dc6001ff605e",
    "serialized": "lnbc1pwtt3wspp5elwc50nuxpzlc87fag53mqm25cv96ek2l26xl4w9eca47gw9504sdq2wpskwctddyxqr4rqrzjqwryaup9lh50kkranzgcdnn2fgvx390wgj5jd07rwr3vxeje0glc7z9rtvqqwngqqqqqqqlgqqqqqeqqjqw5axdq7sfenm4zwplmxneu5q2fggj8yvltrt6ckggpll8qxqdaz5duetw998vy0t3f4guyms439p3e3jhaq3khl7vfzwjwghe5hqtmgpqeme4a",
    "description": "A payment description",
    "paymentHash": "cfdd8a3e7c3045fc1fc9ea291d836aa6185d66cafab46fd5c5ce3b5f21c5a3eb",
    "expiry": 21600
  },
  {
    "prefix": "lnbc",
    "timestamp": 1555416528,
    "nodeId": "036ded9bb8175d0c9fd3fad145965cf5005ec599570f35c682e710dc6001ff605e",
    "serialized": "lnbc1pwtt3wspp5elwc50nuxpzlc87fag53mqm25cv96ek2l26xl4w9eca47gw9504sdq2wpskwctddyxqr4rqrzjqwryaup9lh50kkranzgcdnn2fgvx390wgj5jd07rwr3vxeje0glc7z9rtvqqwngqqqqqqqlgqqqqqeqqjqw5axdq7sfenm4zwplmxneu5q2fggj8yvltrt6ckggpll8qxqdaz5duetw998vy0t3f4guyms439p3e3jhaq3khl7vfzwjwghe5hqtmgpqeme4a",
    "description": "wassa wassa",
    "paymentHash": "cfdd8a3e7c3045fc1fc9ea291d836aa6185d66cafab46fd5c5ce3b5f21c5a3eb"
  }
]
```

Returns all non paid, non expired **BOLT11** invoices stored, the result can be filtered by dates and is outputted in descending
order.

### HTTP Request

`POST http://localhost:8080/listpendinginvoices`

### Parameters

Parameter | Description                                           | Optional | Type
--------- | ----------------------------------------------------- | -------- | -----------------------------------
from      | Filters elements no older than this unix-timestamp    | Yes      | Unix timestamp in seconds (Integer)
to        | Filters elements no younger than this unix-timestamp  | Yes      | Unix timestamp in seconds (Integer)

# Route

## FindRoute

```shell
curl -u :<eclair_api_password> -X POST -F invoice=<some_bolt11invoice> "http://localhost:8080/findroute"

# with eclair-cli
eclair-cli findroute --invoice=<some_bolt11invoice>
```

> The above command returns:

```json
[
  "036d65409c41ab7380a43448f257809e7496b52bf92057c09c4f300cbd61c50d96",
  "03864ef025fde8fb587d989186ce6a4a186895ee44a926bfc370e2c366597a3f8f",
  "03d06758583bb5154774a6eb221b1276c9e82d65bbaceca806d90e20c108f4b1c7"
]
```

Finds a route to the node specified by the invoice, if the invoice does not specify an amount
you must do so via the `amountMsat` parameter.

### HTTP Request

`POST http://localhost:8080/findroute`

### Parameters

Parameter  | Description                                 | Optional | Type
---------- | ------------------------------------------- | -------- | ----------------------
invoice    | The invoice containing the destination      | No       | String
amountMsat | The amount that should go through the route | Yes      | Millisatoshi (Integer)

## FindRouteToNode

```shell
curl -u :<eclair_api_password> -X POST -F nodeId=<some_node> \
     -F amountMsat=<some_amount> "http://localhost:8080/findroutetonode"

# with eclair-cli
eclair-cli --nodeId=<some_node> --amountMsat=<some_amount>
```

> The above command returns:

```json
[
  "036d65409c41ab7380a43448f257809e7496b52bf92057c09c4f300cbd61c50d96",
  "03864ef025fde8fb587d989186ce6a4a186895ee44a926bfc370e2c366597a3f8f",
  "03d06758583bb5154774a6eb221b1276c9e82d65bbaceca806d90e20c108f4b1c7"
]
```

Finds a route to the node.

### HTTP Request

`POST http://localhost:8080/findroutetonode`

### Parameters

Parameter  | Description                                 | Optional | Type
---------- | ------------------------------------------- | -------- | ---------------------------
nodeId     | The destination of the route                | No       | 33-bytes-HexString (String)
amountMsat | The amount that should go through the route | No       | Millisatoshi (Integer)

# Miscellaneous

## GetNewAddress

```shell
curl -u :<eclair_api_password> -X POST "http://localhost:8080/getnewaddress"

# with eclair-cli
eclair-cli getnewaddress
```

> The above command returns:

```json
"2MsRZ1asG6k94m6GYUufDGaZJMoJ4EV5JKs"
```

Get a new on-chain address from the wallet, this can be used to deposit funds that will later be used
to fund channels. The API is only available with the bitcoin-core watcher type, and the resulting addresses
depend on the configured address-type in `bitcoin.conf`.

### HTTP Request

`POST http://localhost:8080/getnewaddress`

## Audit

```shell
curl -u :<eclair_api_password> -X POST "http://localhost:8080/audit"

# with eclair-cli
eclair-cli audit
```

> The above command returns:

```json
{
   "sent":[
      {
        "type": "payment-sent",
        "id": "c85d70da-d159-4a36-938a-c3d2f967f730",
        "paymentHash": "836c8795e811fe0306fb4fee3ee3d0c5a2529dd4ac90d6aca4bd5faf26790a93",
        "paymentPreimage": "62d6f7517b5066c348cddc0a31ee1ed5e8929de2dbe1c7a24bcb5c7cd0a047e4",
        "recipientAmount": 100000,
        "recipientNodeId": "03d41301628a6086aa7a46a2810e73cc1dcdaf4678ea2b3e805befd3b76af31512",
        "parts": [
          {
            "id": "4e8f2440-dbfd-4e76-bb45-a0647a966b2a",
            "amount": 40000,
            "feesPaid": 50,
            "toChannelId": "abab001395fe3e94f58712d65065f362f42e670c45b9e284398866139d98f379",
            "timestamp": 1569337871061
          },
          {
            "id": "63e9b037-92d6-4f3d-b484-41740fc278f5",
            "amount": 60000,
            "feesPaid": 10,
            "toChannelId": "abab001395fe3e94f58712d65065f362f42e670c45b9e284398866139d98f379",
            "timestamp": 1569337871061
          }
        ]
      }
   ],
   "received":[
    {
      "type": "payment-received",
      "paymentHash": "5271e28256bf0511921a11fc15984b260937f4bf42e85bac31bc4da7a89c6bc3",
      "parts": [
        {
          "amount": 1000,
          "fromChannelId": "abab001395fe3e94f58712d65065f362f42e670c45b9e284398866139d98f379",
          "timestamp": 1569338275801
        },
        {
          "amount": 1500,
          "fromChannelId": "8ca125b6ae86c44021337c273bd620e88263d4769de35cccded3cdcb9d1cdcc4",
          "timestamp": 1569338275803
        }
      ]
    }
   ],
   "relayed":[
      {
         "type": "payment-relayed",
         "amountIn":150001,
         "amountOut":150000,
         "paymentHash":"427309c52a46f8c005ad840c106fcdc9c4c60f95769525bc91c4a742133e4fe3",
         "fromChannelId":"56d7d6eda04d80138270c49709f1eadb5ab4939e5061309ccdacdb98ce637d0e",
         "toChannelId":"56d7d6eda04d80138270c49709f1eadb5ab4939e5061309ccdacdb98ce637d0e",
         "timestamp":1553527391064
      }
   ]
}
```

Retrieves information about payments handled by this node such as: sent, received and relayed payments.
All monetary values are expressed in millisatoshi.

### HTTP Request

`POST http://localhost:8080/audit`

### Parameters

Parameter | Description                                           | Optional | Type
--------- | ----------------------------------------------------- | -------- | -----------------------------------
from      | Filters elements no older than this unix-timestamp    | Yes      | Unix timestamp in seconds (Integer)
to        | Filters elements no younger than this unix-timestamp  | Yes      | Unix timestamp in seconds (Integer)

## NetworkFees

```shell
curl -u :<eclair_api_password> -X POST "http://localhost:8080/networkfees"

# with eclair-cli
eclair-cli networkfees
```

> The above command returns:

```json
[
  {
    "remoteNodeId": "03864ef025fde8fb587d989186ce6a4a186895ee44a926bfc370e2c366597a3f8f",
    "channelId": "57d7d6eda04d80138270c49709f1eadb5ab4939e5061309ccdacdb98ce637d0e",
    "txId": "0e7d63ce98dbaccd9c3061509e93b45adbeaf10997c4708213804da0edd6d757",
    "fee": 3382,
    "txType": "funding",
    "timestamp": 1551798422110
  }
]
```

Retrieves information about on-chain fees paid during channel operations, currency values are in Satoshis.

### HTTP Request

`POST http://localhost:8080/networkfees`

### Parameters

Parameter | Description                                           | Optional | Type
--------- | ----------------------------------------------------- | -------- | -----------------------------------
from      | Filters elements no older than this unix-timestamp    | Yes      | Unix timestamp in seconds (Integer)
to        | Filters elements no younger than this unix-timestamp  | Yes      | Unix timestamp in seconds (Integer)

## ChannelStats

```shell
curl -u :<eclair_api_password> -X POST "http://localhost:8080/channelstats"

# with eclair-cli
eclair-cli channelstats
```

> The above command returns:

```json
[
  {
    "channelId": "57d7d6eda04d80138270c49709f1eadb5ab4939e5061309ccdacdb98ce637d0e",
    "avgPaymentAmount": 123,
    "paymentCount": 55,
    "relayFee": 3,
    "networkFee": 3382
  }
]
```

Retrieves information about local channels, the information is then aggregated in order to display
statistics about the routing activity of the channels, values are in Satoshis.

### HTTP Request

`POST http://localhost:8080/channelstats`

## UsableBalances

```shell
curl -u :<eclair_api_password> -X POST "http://localhost:8080/usablebalances"

# with eclair-cli
eclair-cli usablebalances
```

> The above command returns:

```json
[
  {
    "remoteNodeId": "03864ef025fde8fb587d989186ce6a4a186895ee44a926bfc370e2c366597a3f8f",
    "shortChannelId": "562890x809x0",
    "canSend": 131219000,
    "canReceive": 466000,
    "isPublic": true
  }
]
```

Retrieves information about the available balance of local channels.

### HTTP Request

`POST http://localhost:8080/usablebalances`

# Websocket

## WS

> Payment relayed event

```json
{
   "type": "payment-relayed",
   "amountIn": 21,
   "amountOut": 20,
   "paymentHash": "0000000000000000000000000000000000000000000000000000000000000000",
   "fromChannelId": "0000000000000000000000000000000000000000000000000000000000000000",
   "toChannelId": "0100000000000000000000000000000000000000000000000000000000000000",
   "timestamp": 1553784963659
}
```

> Payment received event

```json
{
   "type": "payment-received",
   "paymentHash": "0000000000000000000000000000000000000000000000000000000000000000",
   "parts":[
     {
       "amount": 21,
       "fromChannelId": "0100000000000000000000000000000000000000000000000000000000000000",
       "timestamp": 1553784963659
     },
     {
       "amount": 24,
       "fromChannelId": "0200000000000000000000000000000000000000000000000000000000000000",
       "timestamp": 1553784963873
     }
   ]
}
```

> Payment failed event

```json
{
   "type":"payment-failed",
   "id":"487da196-a4dc-4b1e-92b4-3e5e905e9f3f",
   "paymentHash":"0000000000000000000000000000000000000000000000000000000000000000",
   "failures":[],
   "timestamp":1553784963659
}
```

> Payment sent event

```json
{
   "type":"payment-sent",
   "id":"487da196-a4dc-4b1e-92b4-3e5e905e9f3f",
   "paymentHash":"0000000000000000000000000000000000000000000000000000000000000000",
   "paymentPreimage":"0100000000000000000000000000000000000000000000000000000000000000",
   "recipientAmount": 45,
   "recipientNodeId": "02fef72bab86280a4fe30f2710b23a5d4c0d17f5adc4e1f24adabfd16cf6bd3c35",
   "parts":[
     {
       "id":"b8799834-8db9-460b-b754-2942f20e3500",
       "amount":21,
       "feesPaid":1,
       "toChannelId":"0100000000000000000000000000000000000000000000000000000000000000",
       "timestamp":1553784337711
     },
     {
       "id":"ab348eb7-b0ed-46ff-9274-28cfdbdaae8d",
       "amount":24,
       "feesPaid":3,
       "toChannelId":"0200000000000000000000000000000000000000000000000000000000000000",
       "timestamp":1553784337735
     }
   ]
}
```

> Payment settling on-chain event

```json
{
   "type":"payment-settling-onchain",
   "amount":21,
   "paymentHash":"0100000000000000000000000000000000000000000000000000000000000000",
   "timestamp":1553785442676
}
```

This is a simple [websocket](https://tools.ietf.org/html/rfc6455) that will output payment related events, it supports
several types covering all the possible outcomes. All monetary values are expressed in millisatoshi.

### Response types

Type                     | Description
------------------------ | ------------------------------------------------------------------
payment-received         | A payment has been received  
payment-relayed          | A payment has been successfully relayed
payment-sent             | A payment has been successfully sent
payment-settling-onchain | A payment wasn't fulfilled and its HTLC is being redeemed on-chain
payment-failed           | A payment failed

### HTTP Request

`GET ws://localhost:8080/ws`