import {AppRegistry, Image} from 'react-native';
import App from './App';
import {name as appName} from './app.json';
import {PluginManager} from 'sn-plugin-lib';
// Side-effect import: installs the single PluginManager.registerButtonListener
// used by SuperCanvasScreen (id=500) and prefixes dispatch logs with
// [PLUGIN_ROUTER] for logcat searchability. Pattern copied from sn-shapes.
import {installPluginRouter} from './src/pluginRouter';

const BUTTON_TYPE_TOOLBAR = 1;
const BUTTON_TYPE_LASSO = 2;
const TOOLBAR_BUTTON_ID = 500; // Chosen to avoid colliding with sibling plugins' ids (sn-shapes uses 100).
// v1d (FR13): a second, distinct button id for the lasso-toolbar "reopen"
// entry point — see src/pluginRouter.ts / SuperCanvasScreen.tsx for how the
// two button ids are distinguished to pick the right canvasId to load.
const LASSO_REOPEN_BUTTON_ID = 501;
const SHOW_TYPE_WITH_UI = 1;
// regionType 3 = "Fullscreen display, won't dismiss" (NativePluginManager.d.ts).
// SuperCanvas is a persistent full-screen canvas (spec/SuperCanvas-PRD.md
// journey step 3), not a popup like Shapes/Mindmap, so it needs this rather
// than the default popup region.
const REGION_TYPE_FULLSCREEN_PERSISTENT = 3;
// Lasso toolbar `editDataTypes` value for "Image" (NativePluginManager.d.ts's
// registerButtonRes doc comment: 0=strokes, 1=title, 2=image, 3=text, 4=link).
// Scopes this button to only appear when the user lassos a SuperCanvas
// thumbnail sticker (a Picture element), not arbitrary lasso selections.
const EDIT_DATA_TYPE_IMAGE = 2;

AppRegistry.registerComponent(appName, () => App);

PluginManager.init();
installPluginRouter();

// TODO: once saveCanvas/loadCanvas/exportPdf/generateThumbnail (SuperCanvasModule.kt)
// are implemented for real (see spec/SuperCanvas-PRD.md §11), declare and request
// `plugin.permission.FILE:WRITE` here following sn-shapes' requestFilePermissions
// pattern — nothing in this v0 scaffold touches the filesystem yet, so no
// permission is declared (PluginConfig.json has no `uses-permissions`), matching
// the "declare only what's used" rule.

PluginManager.registerButton(BUTTON_TYPE_TOOLBAR, ['NOTE'], {
  id: TOOLBAR_BUTTON_ID,
  name: 'SuperCanvas',
  icon: Image.resolveAssetSource(require('./assets/icon.png')).uri,
  showType: SHOW_TYPE_WITH_UI,
  regionType: REGION_TYPE_FULLSCREEN_PERSISTENT,
});

// v1d (FR13): lasso-toolbar reopen entry point. Only surfaces when the user
// lassos an image (a SuperCanvas thumbnail sticker inserted via "Save to
// Note" — see SuperCanvasScreen.tsx); on tap, the plugin opens and reads the
// lassoed element's userData (via PluginCommAPI.getLassoElements()) to pick
// which canvas to load instead of the default one.
PluginManager.registerButton(BUTTON_TYPE_LASSO, ['NOTE'], {
  id: LASSO_REOPEN_BUTTON_ID,
  name: 'Open Canvas',
  icon: Image.resolveAssetSource(require('./assets/icon.png')).uri,
  showType: SHOW_TYPE_WITH_UI,
  editDataTypes: [EDIT_DATA_TYPE_IMAGE],
  regionType: REGION_TYPE_FULLSCREEN_PERSISTENT,
});
