import VideoList from './pages/VideoList'
import PlaylistList from './pages/PlaylistList'
import AudioArtists from './pages/AudioArtists'
import AudioAlbums from './pages/AudioAlbums'
import AudioTracks from './pages/AudioTracks'
import AudioGenres from './pages/AudioGenres'
import PageDownloads from './pages/PageDownloads'

const routes = [
    { path: '/', redirect: '/videos', name: 'Home' },
    { path: '/videos', component: VideoList, name: 'VideoList', meta: { showDisplayBar: true } },
    { path: '/playlists', component: PlaylistList, name: 'PlaylistList', meta: { showDisplayBar: true } },
    {
        path: '/audio', redirect: '/audio/artists', name: 'AudioArtists',
        children: [
            { path: 'artists', component: AudioArtists, name: 'AudioArtists', meta: { showDisplayBar: true, isAudio: true } },
            { path: 'albums', component: AudioAlbums, name: 'AudioAlbums', meta: { showDisplayBar: true, isAudio: true } },
            { path: 'tracks', component: AudioTracks, name: 'AudioTracks', meta: { showDisplayBar: true, isAudio: true } },
            { path: 'genres', component: AudioGenres, name: 'AudioGenres', meta: { showDisplayBar: true, isAudio: true } },
        ]
    },

    { path: '/logs', component: PageDownloads, name: 'Logs' },
]

export default routes;