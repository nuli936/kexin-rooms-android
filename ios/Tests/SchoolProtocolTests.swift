import Foundation
import XCTest
@testable import SchoolProtocol

final class SchoolProtocolTests: XCTestCase {
    private let html = """
    <input id="xnm" value="2026"><input id="xqm" value="3">
    <select id="xqh_id"><option value="106">东校区(科信学院)</option></select>
    <select id="dm_cx"><option value="2026-3">2026-2027学年第一学期</option><option value="2027-3">未来学年</option></select>
    <select id="cdlb_id"><option value="">全部场地类别</option><option value="03">多媒体教室</option></select>
    """

    private let metadata: [String: Any] = [
        "lhList": [["XQH_ID": "106", "JXLDM": "19", "JXLMC": "东校区北楼"]],
        "jcList": [["JCMC": 1, "SJD": "08:30-09:15"], ["JCMC": 2, "SJD": "09:20-10:05"]]
    ]
    private let term: [String: Any] = [
        "dqzcxq": ["ZXRQ": "2026-08-31", "ZQST": "1", "ZXZC": "1"],
        "nxqzcList": [["dxqzc": "1", "zczt": "3"], ["dxqzc": "4", "zczt": "3"]]
    ]

    func testCurrentSchoolYearAndPeriodBitmask() throws {
        let config = try SchoolProtocol.config(html: html, metadata: metadata, termData: term)
        XCTAssertEqual(config.year, "2026")
        XCTAssertEqual(config.term, "3")
        var calendar = SchoolProtocol.calendar
        calendar.timeZone = TimeZone(identifier: "Asia/Shanghai")!
        let date = calendar.date(from: DateComponents(year: 2026, month: 9, day: 21))!
        let form = Dictionary(uniqueKeysWithValues: try SchoolProtocol.query(config, date: date, from: 1, to: 2,
                                                                               building: "19", type: "03", page: 1))
        XCTAssertEqual(form["xqh_id"], "106")
        XCTAssertEqual(form["xnm"], "2026")
        XCTAssertEqual(form["zcd"], "8")
        XCTAssertEqual(form["xqj"], "1")
        XCTAssertEqual(form["jcd"], "3")
    }

    func testRejectsOtherCampusAndIncompletePage() throws {
        let record: [String: Any] = ["totalCount": 1, "totalPage": 1, "currentPage": 1,
                                     "items": [["cd_id": "8", "cdmc": "A11-101", "xqh_id": "106",
                                                "xqmc": "东校区(科信学院)"]]]
        XCTAssertEqual(try SchoolProtocol.page(record).rooms.count, 1)
        var changed = record
        changed["items"] = [["cd_id": "8", "cdmc": "A11-101", "xqh_id": "101", "xqmc": "主校区"]]
        XCTAssertThrowsError(try SchoolProtocol.page(changed))
    }

    func testRejectsDateOutsideSchoolWeeks() throws {
        let config = try SchoolProtocol.config(html: html, metadata: metadata, termData: term)
        let date = SchoolProtocol.calendar.date(from: DateComponents(year: 2026, month: 10, day: 5))!
        XCTAssertThrowsError(try SchoolProtocol.query(config, date: date, from: 1, to: 2,
                                                      building: "19", type: "03", page: 1))
    }
}
