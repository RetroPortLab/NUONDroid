Better beat detection with Spotify when capture isn't available.

- When NUONDroid can't capture Spotify's sound directly (you declined screen capture, Spotify blocks it, or a Chromecast cast took it over), it uses Android's Visualizer. That used to give only 20 short snapshots a second, which missed most drum hits: on real songs VLM found about half the beats and rarely locked onto the tempo.
- It now reads the newest sound every 15 ms, giving VLM an unbroken signal. In testing with real songs, it catches the same drum hits as full capture and locks onto the tempo again.
- Full capture and your own files were checked too and are unchanged.
