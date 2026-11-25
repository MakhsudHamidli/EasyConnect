// Release rejimində Windows-da əlavə konsol pəncərəsi açılmasın.
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

fn main() {
    easyconnect_lib::run()
}
