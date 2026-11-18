package dev.optrolley.autoroute.ui

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import dev.optrolley.core.Barcode
import dev.optrolley.core.ChainId
import dev.optrolley.core.ItemKey
import dev.optrolley.core.SectionKind
import dev.optrolley.core.StoreId

/**
 * Stable test tags. They double as uiautomator resource ids on the device, so
 * renaming one breaks the scripts that drive the real phone.
 */
object Tags {
    const val STORE_PICKER = "store-picker"
    const val STORE_OPTION_NONE = "store-option:none"
    fun storeOption(id: StoreId) = "store-option:${id.value}"
    const val MANAGE_STORES = "manage-stores"

    const val OVERFLOW = "overflow"
    const val MENU_STORES = "menu-stores"
    const val MENU_ROUTING = "menu-routing"
    const val MENU_LIBRARY = "menu-library"
    const val MENU_EXPORT = "menu-export"
    const val MENU_IMPORT = "menu-import"
    const val MENU_SETTINGS = "menu-settings"
    const val MENU_DEBUG = "menu-debug"
    const val DEBUG_BENCH_RUN = "debug-bench-run"
    const val DEBUG_BENCH_OUT = "debug-bench-out"

    const val LIST = "list"
    const val EMPTY_LIST = "empty-list"
    const val ELSEWHERE_BANNER = "elsewhere-banner"
    const val FINISH_TRIP = "finish-trip"
    fun section(kind: SectionKind) = "section:${kind.name}"

    fun entry(item: ItemKey) = "entry:${item.value}"
    fun tick(item: ItemKey) = "tick:${item.value}"
    fun entryMenu(item: ItemKey) = "entry-menu:${item.value}"
    fun actionEdit(item: ItemKey) = "action-edit:${item.value}"
    fun actionRemove(item: ItemKey) = "action-remove:${item.value}"
    fun actionNotHereStore(item: ItemKey) = "action-not-here-store:${item.value}"
    fun actionNotHereChain(item: ItemKey) = "action-not-here-chain:${item.value}"
    fun actionAvailable(item: ItemKey) = "action-available:${item.value}"

    const val EDIT_NAME = "edit-name"
    const val EDIT_QUANTITY = "edit-quantity"
    const val EDIT_NOTE = "edit-note"
    const val EDIT_SAVE = "edit-save"
    const val EDIT_CANCEL = "edit-cancel"

    const val ADD_FIELD = "add-field"
    const val ADD_BUTTON = "add-button"
    const val ADD_DETAILS = "add-details"
    const val ADD_QUANTITY = "add-quantity"
    const val ADD_NOTE = "add-note"
    fun suggestion(item: ItemKey) = "suggestion:${item.value}"

    const val BACK = "back"
    const val ADD_STORE = "add-store"
    fun store(id: StoreId) = "store:${id.value}"
    fun chain(id: ChainId) = "chain:${id.value}"
    const val STORE_NAME_FIELD = "store-name-field"
    const val CHAIN_PICKER = "chain-picker"
    const val CHAIN_OPTION_NONE = "chain-option:none"
    const val CHAIN_OPTION_NEW = "chain-option:new"
    fun chainOption(id: ChainId) = "chain-option:${id.value}"
    const val NEW_CHAIN_FIELD = "new-chain-field"
    const val STORE_SAVE = "store-save"
    const val STORE_CANCEL = "store-cancel"
    const val CHAIN_NAME_FIELD = "chain-name-field"
    const val CHAIN_SAVE = "chain-save"
    const val CHAIN_CANCEL = "chain-cancel"

    const val SCAN_BUTTON = "scan"
    const val SCAN_MINIMAP = "scan-minimap"
    const val SCAN_MODE_ADD = "scan-mode:add"
    const val SCAN_MODE_TICK = "scan-mode:tick"
    fun scanEvent(id: Long) = "scan-event:$id"
    fun scanUndo(id: Long) = "scan-undo:$id"
    fun scanChange(id: Long) = "scan-change:$id"
    const val NAMING_FIELD = "naming-field"
    const val NAMING_SAVE = "naming-save"
    const val NAMING_CANCEL = "naming-cancel"
    fun namingSuggestion(item: ItemKey) = "naming-suggestion:${item.value}"

    fun router(id: String) = "router:$id"
    fun lab(id: String) = "lab:$id"

    const val LIBRARY = "library"
    const val LIBRARY_EMPTY = "library-empty"
    fun libraryItem(item: ItemKey) = "library-item:${item.value}"
    fun libraryRename(item: ItemKey) = "library-rename:${item.value}"
    fun libraryCode(code: Barcode) = "library-code:${code.value}"
    fun libraryUnlink(code: Barcode) = "library-unlink:${code.value}"
    fun libraryLookUp(code: Barcode) = "library-look-up:${code.value}"
    fun libraryProduct(code: Barcode) = "library-product:${code.value}"
    fun libraryPicture(code: Barcode) = "library-picture:${code.value}"
    const val LIBRARY_LOOK_UP_ALL = "library-look-up-all"
    const val LIBRARY_PROGRESS = "library-progress"
    const val LIBRARY_STOPPED = "library-stopped"
    const val LIBRARY_CLEAR = "library-clear"
    const val LIBRARY_SOURCE = "library-source"
    const val RENAME_NAME = "rename-name"
    const val RENAME_SAVE = "rename-save"
    const val RENAME_CANCEL = "rename-cancel"
    const val JOIN_CONFIRM = "join-confirm"
    const val JOIN_CANCEL = "join-cancel"

    const val SETTINGS = "settings"
    const val SETTING_LOOK_UPS = "setting-look-ups"
}

/**
 * Exposes test tags as resource ids. Dialogs and menus are separate windows,
 * so each of their roots needs this too, not only the activity's.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.tagsAsResourceIds(): Modifier = semantics { testTagsAsResourceId = true }
