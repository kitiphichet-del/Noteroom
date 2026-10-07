# Live Lecture Notes for Android

แอปจดบันทึกคำบรรยายจากเสียงเป็นข้อความแบบสด โดยใช้ Android `SpeechRecognizer` และ `onPartialResults` เพื่อแสดงข้อความระหว่างพูด

## จุดเด่น
- แสดงข้อความแบบสดระหว่างพูด (partial results)
- หน่วงการแสดงผล 0 / 0.25 / 0.5 วินาที เพื่อลดข้อความกระโดด
- ฟังต่ออัตโนมัติเมื่อระบบแบ่งช่วงเสียงหรือพบ silence
- ภาษาไทยเป็นค่าเริ่มต้น พร้อม English และจีนตัวย่อ
- ไม่แสดง Error 7 ให้รบกวนผู้ใช้; กรณี no-match จะเริ่มฟังต่ออัตโนมัติ
- หมุนหน้าจอแล้ว Activity ไม่ถูกสร้างใหม่
- เก็บ draft อัตโนมัติในเครื่อง
- คัดลอก / ล้าง / ส่งออกเป็น `.txt`
- เปิดหน้าจอค้างระหว่างกำลังจดบรรยาย

## ข้อจำกัดที่ควรรู้
`SpeechRecognizer` ให้ผลลัพธ์แบบ hypothesis ระหว่างพูด ดังนั้นคำล่าสุดอาจถูกแก้เองเมื่อระบบได้ยินบริบทเพิ่มขึ้น นี่เป็นพฤติกรรมปกติของ speech-to-text แบบสด และไม่ใช่การจับ phoneme แบบคำต่อคำ 100% ทุกมิลลิวินาที

โหมดออฟไลน์ขึ้นกับ Speech Recognition Service และ language pack ของโทรศัพท์ แม้แอปจะตั้ง `EXTRA_PREFER_OFFLINE=true` แล้วก็ตาม บางเครื่องอาจยังใช้เครือข่ายหรือไม่รองรับภาษาไทยออฟไลน์

## Build APK ด้วย GitHub Actions
โปรเจกต์มี `.github/workflows/build-apk.yml` ให้แล้ว เมื่อ push เข้า branch `main` จะสร้าง artifact ชื่อ `LiveLectureNotes-debug-apk`

## Android Studio
เปิดโฟลเดอร์นี้ด้วย Android Studio, Sync Gradle แล้ว Run บนอุปกรณ์ Android 6.0+ (minSdk 23)
