# Changelog

## [0.2.1](https://github.com/matinzd/airplay-android-tv/compare/v0.2.0...v0.2.1) (2026-10-26)


### Bug fixes

* **audio:** resync after a run of late drops while mirroring ([#3](https://github.com/matinzd/airplay-android-tv/issues/3)) ([28a3a74](https://github.com/matinzd/airplay-android-tv/commit/28a3a74ff1d4f4daee571f2f005ec3acd1165cd2))
* **video:** request low latency only from decoders that support it ([#2](https://github.com/matinzd/airplay-android-tv/issues/2)) ([4486203](https://github.com/matinzd/airplay-android-tv/commit/44862031976e4d423f1baf9e8112b17e9f488a5f))

## [0.2.0](https://github.com/matinzd/airplay-android-tv/compare/v0.1.0...v0.2.0) (2026-10-18)


### Features

* **audio:** let the sender's volume step the TV or receiver ([f42b7c9](https://github.com/matinzd/airplay-android-tv/commit/f42b7c9fcdf4a283f2fe91df8a32c1816428bf2c))
* **remote:** control AirPlay music with the TV remote; protect OLED TVs ([8c009bc](https://github.com/matinzd/airplay-android-tv/commit/8c009bc6e7400f6f4187a65de4f24a19c60d7971))
* **ui:** fill portrait mirroring side bars with a blurred copy ([48d3ffd](https://github.com/matinzd/airplay-android-tv/commit/48d3ffdbeb2684642847a142da04083296d2ee68))
* **ui:** redesign the screens for the TV ([790d882](https://github.com/matinzd/airplay-android-tv/commit/790d8829f154af41bdeb631e3b761c479a034460))
* **ui:** show playing/paused, track progress and remote controls ([d69cb26](https://github.com/matinzd/airplay-android-tv/commit/d69cb26f3b2eff15b1075d20c44f43bfd8ceac9a))
* **video:** show a message when AirPlay video can't play ([d6a1977](https://github.com/matinzd/airplay-android-tv/commit/d6a19779698458eaf21d6f2c933741b6ec070268))


### Bug fixes

* **pin:** keep the code valid until the device pairs ([cf5e784](https://github.com/matinzd/airplay-android-tv/commit/cf5e78407b6918fbb44772286ba113b02a392c0a))
* **pin:** keep the pairing code on screen until it is entered ([02271a6](https://github.com/matinzd/airplay-android-tv/commit/02271a6e8c7c6f926c0c89004ee7c3180a7f1fc0))
* **remote:** look for the sender again when a key press has no target ([1b46ad1](https://github.com/matinzd/airplay-android-tv/commit/1b46ad157d7d7925338cc61584dcdf10baa99b56))
* **remote:** match DACP-IDs that start with a zero ([968527b](https://github.com/matinzd/airplay-android-tv/commit/968527b2fa983f600eb9acf413b67894d2fec9fe))
* **remote:** pause music on Back instead of dropping the connection ([6f4fa37](https://github.com/matinzd/airplay-android-tv/commit/6f4fa372f36daaedf6038cafaf487dda926eef00))
* **ui:** end long track info with an ellipsis ([457bc1e](https://github.com/matinzd/airplay-android-tv/commit/457bc1eb7aefd8884d9d0d203b40f6b2a32bb6c9))
* **ui:** keep the album art while music is paused ([973c71e](https://github.com/matinzd/airplay-android-tv/commit/973c71e9c10aa3ff02525695adfdd11c17b1414b))
* **ui:** return to a running AirPlay session from the launcher ([286fb95](https://github.com/matinzd/airplay-android-tv/commit/286fb9572926a04c070ca4cbc143a4b5565d13b2))
* **ui:** scroll long titles instead of wrapping them ([b2c9d63](https://github.com/matinzd/airplay-android-tv/commit/b2c9d637516e0bc2ec89952fd0c4ad8d69c4226f))
* **ui:** stop clipping the focused Settings button ([82fadaa](https://github.com/matinzd/airplay-android-tv/commit/82fadaa3e6cc2af649ea07424b19b22338c21b46))
* **ui:** stop clipping the play/pause button when it bounces ([cf11a0d](https://github.com/matinzd/airplay-android-tv/commit/cf11a0d51a588f729291410f214b6628f2cf1f04))
* **ui:** stop the title marquee from restarting ([7cd2c6f](https://github.com/matinzd/airplay-android-tv/commit/7cd2c6fb9505b3e9def08395fd6635fda9f6e891))


### Performance

* **ui:** update the portrait backdrop up to 30 times a second ([baceff0](https://github.com/matinzd/airplay-android-tv/commit/baceff0a35bb682896846c98b9ebcef7761641d7))


### Reverts

* **audio:** remove sender control of the TV volume ([d38a310](https://github.com/matinzd/airplay-android-tv/commit/d38a310fd1f910c17d8032330183cdeb8bd7697f))
